import { CORRELATION_ID_HEADER, newCorrelationId } from "../api/headers";

const CORRELATION_ID_PATTERN = /^[A-Za-z0-9._:-]{1,128}$/;
const TRACEPARENT_PATTERN = /^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$/;

export function correlationIdFrom(headers: Headers, generate: () => string = newCorrelationId): string {
    const incoming = headers.get(CORRELATION_ID_HEADER);
    return incoming !== null && CORRELATION_ID_PATTERN.test(incoming) ? incoming : generate();
}

export function traceparentFrom(headers: Headers): string | undefined {
    const value = headers.get("traceparent");
    return value !== null && TRACEPARENT_PATTERN.test(value) ? value : undefined;
}
