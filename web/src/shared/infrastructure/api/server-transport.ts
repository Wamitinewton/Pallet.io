import "server-only";

import type { AccessTokenProvider } from "@/shared/application/access-token";
import { CORRELATION_ID_HEADER, newCorrelationId } from "./headers";
import { trimTrailingSlash, type Transport } from "./transport";

export interface ServerTransportOptions {
    readonly gatewayUrl: string;
    readonly tokens: AccessTokenProvider;
    readonly correlationId?: () => string;
    readonly fetch?: (request: Request, init: RequestInit) => Promise<Response>;
}

export function serverTransport({
    gatewayUrl,
    tokens,
    correlationId = newCorrelationId,
    fetch: send = (request, init) => fetch(request, init),
}: ServerTransportOptions): Transport {
    return {
        baseUrl: `${trimTrailingSlash(gatewayUrl)}/api/v1`,
        fetch: (request) => send(request, { cache: "no-store" }),
        middleware: [
            {
                async onRequest({ request }) {
                    const token = await tokens.accessToken();
                    if (token !== undefined) request.headers.set("Authorization", `Bearer ${token}`);
                    if (!request.headers.has(CORRELATION_ID_HEADER)) {
                        request.headers.set(CORRELATION_ID_HEADER, correlationId());
                    }
                    return request;
                },
            },
        ],
    };
}
