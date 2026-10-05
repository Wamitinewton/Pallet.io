import { clientIp } from "@/shared/infrastructure/http/client-ip";
import { correlationIdFrom } from "@/shared/infrastructure/http/correlation";
import type { RequestOrigin } from "../application/ports";

export interface ResolvedRequestOrigin extends RequestOrigin {
    readonly clientIp: string | undefined;
    readonly correlationId: string;
}

export function requestOriginFrom(headers: Headers, trustedProxyHops: number): ResolvedRequestOrigin {
    return {
        clientIp: clientIp(headers.get("X-Forwarded-For"), trustedProxyHops),
        correlationId: correlationIdFrom(headers),
    };
}
