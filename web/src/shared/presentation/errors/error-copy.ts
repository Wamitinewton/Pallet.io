import { ApiError, correlationIdOf, NetworkError, SessionExpiredError } from "@/shared/domain/errors";

export type ErrorCopy = Readonly<Partial<Record<string, string | ((error: ApiError) => string)>>>;

export const SESSION_EXPIRED_COPY = "Your session has ended. Sign in again to continue.";
export const NETWORK_COPY = "We couldn't reach Pallet. Check your connection and try again.";
export const GENERIC_COPY = "Something went wrong on our side. Try again in a moment.";

export function tooManyAttemptsCopy(seconds: number): string {
    return `Too many attempts. Try again in ${String(seconds)} second${seconds === 1 ? "" : "s"}.`;
}

const DEFAULT_COPY: ErrorCopy = {
    TOO_MANY_REQUESTS: (error) => {
        const seconds = error.retryAfterSeconds();
        return seconds === undefined
            ? "Too many requests. Wait a moment and try again."
            : `Too many requests. Try again in ${String(seconds)} second${seconds === 1 ? "" : "s"}.`;
    },
    ACCESS_DENIED: "You don't have permission to do that.",
    FORBIDDEN: "You don't have permission to do that.",
    NOT_FOUND: "We couldn't find that. It may have been deleted.",
    ROUTE_NOT_FOUND: "We couldn't find that. It may have been deleted.",
    SERVICE_UNAVAILABLE: "Pallet is temporarily unavailable. Try again in a moment.",
    EXTERNAL_SERVICE_ERROR: "A service Pallet depends on didn't respond. Try again in a moment.",
};

export interface MessageOptions {
    readonly reference?: boolean;
}

export function messageFor(
    error: unknown,
    overrides: ErrorCopy = {},
    { reference = true }: MessageOptions = {},
): string {
    const generic = () => {
        const correlationId = correlationIdOf(error);
        return reference && correlationId !== undefined ? `${GENERIC_COPY} Reference: ${correlationId}` : GENERIC_COPY;
    };

    if (error instanceof SessionExpiredError) return SESSION_EXPIRED_COPY;
    if (error instanceof NetworkError) return NETWORK_COPY;
    if (!(error instanceof ApiError)) return generic();

    const copy = overrides[error.code] ?? DEFAULT_COPY[error.code];
    if (typeof copy === "function") return copy(error);
    if (copy !== undefined) return copy;
    if (error.status < 500 && error.message.trim() !== "") return error.message;
    return generic();
}
