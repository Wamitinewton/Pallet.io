import type { Clock } from "@/shared/domain/clock";
import { ApiError, NetworkError, UnexpectedResponseError } from "@/shared/domain/errors";
import { BodyTooLargeError, readBoundedText } from "@/shared/infrastructure/http/bounded-body";
import { apiErrorResponse, errorResponse, okResponse } from "@/shared/infrastructure/http/error-response";
import { CSRF_REJECTED_CODE, CSRF_REJECTED_MESSAGE, passesCsrfCheck } from "@/shared/infrastructure/http/same-origin";
import type { Logger } from "@/shared/infrastructure/logging/logger";
import type { Reauthenticate } from "../application/reauthenticate";
import type { ResolveSession } from "../application/resolve-session";
import { IDENTITY_MISMATCH, reauthenticationSchema } from "../domain/reauthentication";
import { remainingLifetimeSeconds } from "../domain/session-policy";
import { requestOriginFrom } from "./request-origin";
import { SESSION_EXPIRED_CODE, SESSION_EXPIRED_MESSAGE } from "./revocation";
import type { SessionCookie } from "./session-cookie";
import { sessionLogId } from "./session-keys";

export const MAX_REAUTHENTICATE_BODY_BYTES = 8 * 1024;

export interface ReauthenticateHandlerOptions {
    readonly cookie: SessionCookie;
    readonly resolveSession: ResolveSession;
    readonly reauthenticate: Reauthenticate;
    readonly publicOrigin: string;
    readonly trustedProxyHops: number;
    readonly clock: Clock;
    readonly logger: Logger;
}

function parseJson(text: string): unknown {
    try {
        return JSON.parse(text);
    } catch {
        return undefined;
    }
}

/** `POST /api/session/reauthenticate`: step-up for the signed-in session, keeping its id and cookie. */
export function createReauthenticateHandler({
    cookie,
    resolveSession,
    reauthenticate,
    publicOrigin,
    trustedProxyHops,
    clock,
    logger,
}: ReauthenticateHandlerOptions): (request: Request) => Promise<Response> {
    return async (request) => {
        const path = new URL(request.url).pathname;
        const origin = requestOriginFrom(request.headers, trustedProxyHops);
        const { correlationId } = origin;
        const fail = (status: number, code: string, message: string, headers?: HeadersInit) =>
            errorResponse(status, code, message, { path, correlationId, headers });

        if (!passesCsrfCheck(request.headers, publicOrigin)) {
            return fail(403, CSRF_REJECTED_CODE, CSRF_REJECTED_MESSAGE);
        }

        let text: string;
        try {
            text = await readBoundedText(request, MAX_REAUTHENTICATE_BODY_BYTES);
        } catch (error) {
            if (error instanceof BodyTooLargeError) return fail(413, "PAYLOAD_TOO_LARGE", "Request body is too large");
            throw error;
        }

        const parsed = reauthenticationSchema.safeParse(parseJson(text));
        if (!parsed.success) {
            return errorResponse(400, "VALIDATION_ERROR", "Validation failed.", {
                path,
                correlationId,
                validationErrors: parsed.error.issues.map((issue) => ({
                    field: issue.path.join("."),
                    message: issue.message,
                })),
            });
        }

        const cookieValue = cookie.read(request.headers.get("Cookie"));
        try {
            const resolved = await resolveSession(cookieValue, origin);
            if (resolved.status !== "active") {
                return fail(
                    401,
                    SESSION_EXPIRED_CODE,
                    SESSION_EXPIRED_MESSAGE,
                    resolved.status === "expired" ? { "Set-Cookie": cookie.clear() } : undefined,
                );
            }

            const result = await reauthenticate({
                session: resolved.session,
                password: parsed.data.password,
                origin,
            });
            const session = sessionLogId(resolved.session.id);
            switch (result.status) {
                case "reauthenticated":
                    if (result.previousRevoked) logger.info("session.reauthenticated", { session, correlationId });
                    else logger.warn("session.reauthenticated_previous_not_revoked", { session, correlationId });
                    return okResponse({ ok: true }, "Reauthenticated", {
                        "Set-Cookie": cookie.issue(result.session.id, remainingLifetimeSeconds(result.session, clock)),
                    });
                case "different-user":
                    logger.warn("session.reauthentication_identity_mismatch", { session, correlationId });
                    return fail(403, IDENTITY_MISMATCH, "Those credentials belong to a different account.");
                case "ended":
                    return fail(401, SESSION_EXPIRED_CODE, SESSION_EXPIRED_MESSAGE, { "Set-Cookie": cookie.clear() });
                case "contended":
                    logger.warn("session.reauthentication_contended", { session, correlationId });
                    return fail(503, "SERVICE_UNAVAILABLE", "Confirming your password didn't finish. Try again.");
            }
        } catch (error) {
            if (error instanceof ApiError) {
                logger.info("session.reauthentication_refused", {
                    code: error.code,
                    status: error.status,
                    correlationId,
                });
                return apiErrorResponse(error, { path, correlationId });
            }
            logger.warn("session.reauthentication_failed", { correlationId, error });
            if (error instanceof NetworkError || error instanceof UnexpectedResponseError) {
                return fail(502, "BAD_GATEWAY", "The platform could not be reached");
            }
            return fail(503, "SERVICE_UNAVAILABLE", "Confirming your password is temporarily unavailable. Try again.");
        }
    };
}
