import { CORRELATION_ID_HEADER } from "@/shared/infrastructure/api/headers";
import { errorResponse } from "@/shared/infrastructure/http/error-response";
import { CSRF_REJECTED_CODE, CSRF_REJECTED_MESSAGE, passesCsrfCheck } from "@/shared/infrastructure/http/same-origin";
import type { Logger } from "@/shared/infrastructure/logging/logger";
import type { EndSession } from "../application/end-session";
import { requestOriginFrom } from "./request-origin";
import type { SessionCookie } from "./session-cookie";

export interface LogoutHandlerOptions {
    readonly cookie: SessionCookie;
    readonly endSession: EndSession;
    readonly publicOrigin: string;
    readonly trustedProxyHops: number;
    readonly logger: Logger;
}

/** `POST /api/session/logout`: idempotent, and always clears the cookie, whatever identity or Redis answer. */
export function createLogoutHandler({
    cookie,
    endSession,
    publicOrigin,
    trustedProxyHops,
    logger,
}: LogoutHandlerOptions): (request: Request) => Promise<Response> {
    return async (request) => {
        const origin = requestOriginFrom(request.headers, trustedProxyHops);
        const { correlationId } = origin;

        if (!passesCsrfCheck(request.headers, publicOrigin)) {
            return errorResponse(403, CSRF_REJECTED_CODE, CSRF_REJECTED_MESSAGE, {
                path: new URL(request.url).pathname,
                correlationId,
            });
        }

        const cookieValue = cookie.read(request.headers.get("Cookie"));
        if (cookieValue !== undefined) {
            try {
                const { ended, revoked } = await endSession(cookieValue, origin);
                if (ended && !revoked) logger.warn("session.revoke_failed", { correlationId });
                else if (ended) logger.info("session.ended", { correlationId });
            } catch (error) {
                logger.error("session.end_failed", { correlationId, error });
            }
        }

        return new Response(null, {
            status: 204,
            headers: {
                "Cache-Control": "no-store",
                "Set-Cookie": cookie.clear(),
                [CORRELATION_ID_HEADER]: correlationId,
            },
        });
    };
}
