import type { Clock } from "@/shared/domain/clock";
import { ApiError, NetworkError, UnexpectedResponseError } from "@/shared/domain/errors";
import { BodyTooLargeError, readBoundedText } from "@/shared/infrastructure/http/bounded-body";
import { apiErrorResponse, errorResponse, okResponse } from "@/shared/infrastructure/http/error-response";
import { CSRF_REJECTED_CODE, CSRF_REJECTED_MESSAGE, passesCsrfCheck } from "@/shared/infrastructure/http/same-origin";
import type { Logger } from "@/shared/infrastructure/logging/logger";
import type { StartSession } from "../application/start-session";
import { credentialsSchema } from "../domain/credentials";
import { remainingLifetimeSeconds } from "../domain/session-policy";
import { requestOriginFrom } from "./request-origin";
import type { SessionCookie } from "./session-cookie";
import { sessionLogId } from "./session-keys";

export const MAX_LOGIN_BODY_BYTES = 8 * 1024;

export interface LoginHandlerOptions {
    readonly cookie: SessionCookie;
    readonly startSession: StartSession;
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

/** `POST /api/session/login`: never logs or returns the credentials or the tokens they buy. */
export function createLoginHandler({
    cookie,
    startSession,
    publicOrigin,
    trustedProxyHops,
    clock,
    logger,
}: LoginHandlerOptions): (request: Request) => Promise<Response> {
    return async (request) => {
        const path = new URL(request.url).pathname;
        const origin = requestOriginFrom(request.headers, trustedProxyHops);
        const { correlationId } = origin;
        const fail = (status: number, code: string, message: string) =>
            errorResponse(status, code, message, { path, correlationId });

        if (!passesCsrfCheck(request.headers, publicOrigin)) {
            return fail(403, CSRF_REJECTED_CODE, CSRF_REJECTED_MESSAGE);
        }

        let text: string;
        try {
            text = await readBoundedText(request, MAX_LOGIN_BODY_BYTES);
        } catch (error) {
            if (error instanceof BodyTooLargeError) return fail(413, "PAYLOAD_TOO_LARGE", "Request body is too large");
            throw error;
        }

        const parsed = credentialsSchema.safeParse(parseJson(text));
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

        try {
            const session = await startSession({
                grant: parsed.data,
                origin,
                replacing: cookie.read(request.headers.get("Cookie")),
            });
            logger.info("session.started", { session: sessionLogId(session.id), correlationId });
            return okResponse({ ok: true }, "Signed in", {
                "Set-Cookie": cookie.issue(session.id, remainingLifetimeSeconds(session, clock)),
            });
        } catch (error) {
            if (error instanceof ApiError) {
                logger.info("session.sign_in_refused", { code: error.code, status: error.status, correlationId });
                return apiErrorResponse(error, { path, correlationId });
            }
            logger.warn("session.sign_in_failed", { correlationId, error });
            if (error instanceof NetworkError || error instanceof UnexpectedResponseError) {
                return fail(502, "BAD_GATEWAY", "The platform could not be reached");
            }
            return fail(503, "SERVICE_UNAVAILABLE", "Signing in is temporarily unavailable. Try again.");
        }
    };
}
