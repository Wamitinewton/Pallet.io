import { errorResponse } from "@/shared/infrastructure/http/error-response";
import { isRevokedSessionAnswer, SESSION_EXPIRED_CODE, SESSION_EXPIRED_MESSAGE } from "./revocation";

export type ServerFetch = (request: Request, init: RequestInit) => Promise<Response>;

/** Server-side twin of the BFF's revocation check, so a Server Component prefetch sees `SESSION_EXPIRED` too. */
export function revocationAwareFetch(onRevoked: () => Promise<void>, send: ServerFetch): ServerFetch {
    return async (request, init) => {
        const carriedToken = request.headers.has("Authorization");
        const response = await send(request, init);
        if (!carriedToken || response.status !== 401) return response;

        const body = await response.text();
        if (!isRevokedSessionAnswer(response.status, body, carriedToken)) {
            return new Response(body, {
                status: response.status,
                statusText: response.statusText,
                headers: response.headers,
            });
        }
        await onRevoked();
        return errorResponse(401, SESSION_EXPIRED_CODE, SESSION_EXPIRED_MESSAGE, {
            path: new URL(request.url).pathname,
            correlationId: response.headers.get("X-Correlation-Id") ?? undefined,
        });
    };
}
