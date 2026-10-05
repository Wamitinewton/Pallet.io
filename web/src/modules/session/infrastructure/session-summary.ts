import type { Clock } from "@/shared/domain/clock";
import { errorResponse, okResponse } from "@/shared/infrastructure/http/error-response";
import type { ResolveSession } from "../application/resolve-session";
import { summarize } from "../domain/session";
import { remainingLifetimeSeconds } from "../domain/session-policy";
import { requestOriginFrom } from "./request-origin";
import { SESSION_EXPIRED_CODE, SESSION_EXPIRED_MESSAGE } from "./revocation";
import type { SessionCookie } from "./session-cookie";

export interface SessionSummaryHandlerOptions {
    readonly cookie: SessionCookie;
    readonly resolveSession: ResolveSession;
    readonly trustedProxyHops: number;
    readonly clock: Clock;
}

export function createSessionSummaryHandler({
    cookie,
    resolveSession,
    trustedProxyHops,
    clock,
}: SessionSummaryHandlerOptions): (request: Request) => Promise<Response> {
    return async (request) => {
        const origin = requestOriginFrom(request.headers, trustedProxyHops);
        const cookieValue = cookie.read(request.headers.get("Cookie"));
        const resolved = await resolveSession(cookieValue, origin);

        if (resolved.status !== "active") {
            return errorResponse(401, SESSION_EXPIRED_CODE, SESSION_EXPIRED_MESSAGE, {
                path: new URL(request.url).pathname,
                correlationId: origin.correlationId,
                ...(resolved.status === "expired" ? { headers: { "Set-Cookie": cookie.clear() } } : {}),
            });
        }

        const headers = new Headers();
        if (resolved.refreshed && cookieValue !== undefined) {
            headers.append("Set-Cookie", cookie.issue(cookieValue, remainingLifetimeSeconds(resolved.session, clock)));
        }
        return okResponse(summarize(resolved.session), "Current session", headers);
    };
}
