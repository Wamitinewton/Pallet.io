export const CORRELATION_ID_HEADER = "X-Correlation-Id";
export const IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
export const CONFIRM_SLUG_HEADER = "X-Confirm-Slug";
export const RETRY_AFTER_HEADER = "Retry-After";

export const CSRF_HEADER = "X-Pallet-Request";
export const CSRF_HEADER_VALUE = "1";

export const SESSION_EXPIRED_CODE = "SESSION_EXPIRED";

export function newCorrelationId(): string {
    return crypto.randomUUID();
}
