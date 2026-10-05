import { ApiError, UnexpectedResponseError } from "@/shared/domain/errors";
import { asUserId } from "@/shared/domain/ids";
import type { IdentityClient } from "@/shared/infrastructure/api/clients";
import { unwrap } from "@/shared/infrastructure/api/envelope";
import { CORRELATION_ID_HEADER } from "@/shared/infrastructure/api/headers";
import { z } from "zod";
import { TokenRejectedError, type RequestOrigin, type TokenIssuer } from "../application/ports";
import type { IssuedTokens, TokenClaims } from "../domain/session";

const tokenResponseSchema = z.object({
    accessToken: z.string().min(1),
    expiresIn: z.number().int().positive(),
    refreshToken: z.string().min(1),
    refreshExpiresIn: z.number().int().positive(),
});

const accessTokenClaimsSchema = z.object({
    sub: z.string().min(1),
    email: z.string().min(1).optional(),
    sid: z.string().min(1).optional(),
});

/** Reads the claims without verifying the signature: the gateway verifies; the BFF only manages the session. */
export function readAccessTokenClaims(accessToken: string): TokenClaims | undefined {
    const payload = accessToken.split(".")[1];
    if (payload === undefined) return undefined;
    try {
        const parsed = accessTokenClaimsSchema.safeParse(
            JSON.parse(Buffer.from(payload, "base64url").toString("utf8")),
        );
        if (!parsed.success) return undefined;
        return { subject: asUserId(parsed.data.sub), email: parsed.data.email, sessionId: parsed.data.sid };
    } catch {
        return undefined;
    }
}

function originHeaders({ clientIp, correlationId }: RequestOrigin): Record<string, string> {
    return {
        ...(clientIp === undefined ? {} : { "X-Forwarded-For": clientIp }),
        ...(correlationId === undefined ? {} : { [CORRELATION_ID_HEADER]: correlationId }),
    };
}

function issuedTokens(result: Parameters<typeof unwrap>[0]): IssuedTokens {
    const tokens = unwrap(result, tokenResponseSchema);
    const claims = readAccessTokenClaims(tokens.accessToken);
    if (claims === undefined) {
        throw new UnexpectedResponseError(
            result.response.status,
            "Identity issued an access token without readable claims",
            result.response.headers.get(CORRELATION_ID_HEADER) ?? undefined,
        );
    }
    return {
        accessToken: tokens.accessToken,
        accessExpiresInSeconds: tokens.expiresIn,
        refreshToken: tokens.refreshToken,
        refreshExpiresInSeconds: tokens.refreshExpiresIn,
        claims,
    };
}

const isRejection = (error: unknown) => error instanceof ApiError && (error.status === 400 || error.status === 401);

export function identityTokenIssuer(identity: IdentityClient): TokenIssuer {
    return {
        async login(grant, origin) {
            return issuedTokens(
                await identity.POST("/auth/login", {
                    body: { email: grant.email, password: grant.password },
                    headers: originHeaders(origin),
                }),
            );
        },

        async refresh(refreshToken, origin) {
            try {
                return issuedTokens(
                    await identity.POST("/auth/refresh", { body: { refreshToken }, headers: originHeaders(origin) }),
                );
            } catch (error) {
                if (isRejection(error))
                    throw new TokenRejectedError("Identity refused the refresh token", { cause: error });
                throw error;
            }
        },

        async revoke({ accessToken, refreshToken }, origin) {
            await identity.POST("/auth/logout", {
                body: { refreshToken },
                headers: { ...originHeaders(origin), Authorization: `Bearer ${accessToken}` },
            });
        },
    };
}
