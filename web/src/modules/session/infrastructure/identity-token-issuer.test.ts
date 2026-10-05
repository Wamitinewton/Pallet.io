import { anonymousAccessTokens } from "@/shared/application/access-token";
import { ApiError, NetworkError, UnexpectedResponseError } from "@/shared/domain/errors";
import { createApiClients } from "@/shared/infrastructure/api/clients";
import { serverTransport } from "@/shared/infrastructure/api/server-transport";
import { fakeJwt } from "@/test/jwt";
import { beforeEach, describe, expect, it } from "vitest";
import { TokenRejectedError, type TokenIssuer } from "../application/ports";
import { identityTokenIssuer, readAccessTokenClaims } from "./identity-token-issuer";

const accessToken = fakeJwt({ sub: "user-1", email: "ada@example.com", sid: "kc-1" });

const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const tokens = (overrides: Record<string, unknown> = {}) =>
    json(200, {
        success: true,
        message: "OK",
        data: {
            accessToken,
            expiresIn: 300,
            refreshToken: "refresh-2",
            refreshExpiresIn: 1800,
            tokenType: "Bearer",
            ...overrides,
        },
    });

const failure = (status: number, error: string) =>
    json(status, { success: false, message: error, error, statusCode: status });

let requests: Request[];
let reply: () => Response | Promise<Response>;
let issuer: TokenIssuer;

beforeEach(() => {
    requests = [];
    reply = () => tokens();
    const transport = serverTransport({
        gatewayUrl: "http://gateway.test",
        tokens: anonymousAccessTokens,
        fetch: (request) => {
            requests.push(request);
            return Promise.resolve(reply());
        },
    });
    issuer = identityTokenIssuer(createApiClients(transport).identity);
});

const lastBody = async () => (await requests.at(-1)?.clone().json()) as unknown;

describe("readAccessTokenClaims", () => {
    it("reads sub, email and sid", () => {
        expect(readAccessTokenClaims(accessToken)).toEqual({
            subject: "user-1",
            email: "ada@example.com",
            sessionId: "kc-1",
        });
    });

    it.each(["", "not-a-jwt", "a.!!!.c", fakeJwt({ sub: "" })])("refuses %j", (token) => {
        expect(readAccessTokenClaims(token)).toBeUndefined();
    });
});

describe("identityTokenIssuer", () => {
    it("logs in with the client ip and correlation id forwarded", async () => {
        const issued = await issuer.login(
            { email: "ada@example.com", password: "pw" },
            { clientIp: "203.0.113.7", correlationId: "corr-1" },
        );

        const request = requests.at(-1);
        expect(request?.url).toBe("http://gateway.test/api/v1/identity/auth/login");
        expect(request?.headers.get("X-Forwarded-For")).toBe("203.0.113.7");
        expect(request?.headers.get("X-Correlation-Id")).toBe("corr-1");
        expect(request?.headers.has("Authorization")).toBe(false);
        expect(await lastBody()).toEqual({ email: "ada@example.com", password: "pw" });
        expect(issued).toEqual({
            accessToken,
            accessExpiresInSeconds: 300,
            refreshToken: "refresh-2",
            refreshExpiresInSeconds: 1800,
            claims: { subject: "user-1", email: "ada@example.com", sessionId: "kc-1" },
        });
    });

    it("passes a login failure through as the ApiError it is", async () => {
        reply = () => failure(401, "INVALID_CREDENTIALS");

        await expect(issuer.login({ email: "a@b.c", password: "x" }, {})).rejects.toMatchObject({
            status: 401,
            code: "INVALID_CREDENTIALS",
        });
    });

    it.each([400, 401])("treats a %i on refresh as a rejected token", async (status) => {
        reply = () => failure(status, status === 400 ? "VALIDATION_FAILED" : "INVALID_TOKEN");

        await expect(issuer.refresh("refresh-1", {})).rejects.toBeInstanceOf(TokenRejectedError);
        expect(await lastBody()).toEqual({ refreshToken: "refresh-1" });
    });

    it("keeps a server failure or network failure on refresh transient", async () => {
        reply = () => failure(503, "SERVICE_UNAVAILABLE");
        await expect(issuer.refresh("refresh-1", {})).rejects.toBeInstanceOf(ApiError);

        reply = () => Promise.reject(new TypeError("fetch failed"));
        await expect(issuer.refresh("refresh-1", {})).rejects.toBeInstanceOf(NetworkError);
    });

    it("refuses an access token whose claims cannot be read", async () => {
        reply = () => tokens({ accessToken: "opaque" });

        await expect(issuer.refresh("refresh-1", {})).rejects.toBeInstanceOf(UnexpectedResponseError);
    });

    it("revokes with the bearer token and the refresh token", async () => {
        reply = () => json(200, { success: true, message: "Logged out", data: null });

        await issuer.revoke({ accessToken: "access-1", refreshToken: "refresh-1" }, {});

        expect(requests.at(-1)?.headers.get("Authorization")).toBe("Bearer access-1");
        expect(await lastBody()).toEqual({ refreshToken: "refresh-1" });
    });
});
