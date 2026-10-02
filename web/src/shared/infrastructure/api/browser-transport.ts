import { CSRF_HEADER, CSRF_HEADER_VALUE } from "./headers";
import type { Transport } from "./transport";

export const BFF_BASE_PATH = "/bff";

export function browserTransport({ baseUrl = BFF_BASE_PATH }: { baseUrl?: string } = {}): Transport {
    return {
        baseUrl,
        fetch: (request) => fetch(request, { credentials: "same-origin" }),
        middleware: [
            {
                onRequest({ request }) {
                    request.headers.set(CSRF_HEADER, CSRF_HEADER_VALUE);
                    return request;
                },
            },
        ],
    };
}
