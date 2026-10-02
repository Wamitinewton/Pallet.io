export interface FieldError {
    readonly field: string;
    readonly message: string;
}

export class ApiError extends Error {
    readonly kind = "api";
    override readonly name = "ApiError";

    constructor(
        readonly status: number,
        readonly code: string,
        message: string,
        readonly fieldErrors: readonly FieldError[],
        readonly meta: Readonly<Record<string, unknown>>,
        readonly correlationId: string | undefined,
    ) {
        super(message);
    }

    is(code: string): boolean {
        return this.code === code;
    }

    isClientError(): boolean {
        return this.status >= 400 && this.status < 500;
    }

    retryAfterSeconds(): number | undefined {
        const value = this.meta.retryAfter;
        const seconds = typeof value === "string" ? Number(value) : value;
        return typeof seconds === "number" && Number.isFinite(seconds) && seconds >= 0 ? Math.ceil(seconds) : undefined;
    }
}

export class SessionExpiredError extends Error {
    readonly kind = "session-expired";
    override readonly name = "SessionExpiredError";

    constructor(message = "The session has expired") {
        super(message);
    }
}

export class NetworkError extends Error {
    readonly kind = "network";
    override readonly name = "NetworkError";

    constructor(message = "The request did not reach the server", options?: ErrorOptions) {
        super(message, options);
    }
}

export class UnexpectedResponseError extends Error {
    readonly kind = "unexpected";
    override readonly name = "UnexpectedResponseError";

    constructor(
        readonly status: number,
        message: string,
        readonly correlationId: string | undefined,
    ) {
        super(message);
    }
}

export type AppError = ApiError | SessionExpiredError | NetworkError | UnexpectedResponseError;

export function isAppError(error: unknown): error is AppError {
    return (
        error instanceof ApiError ||
        error instanceof SessionExpiredError ||
        error instanceof NetworkError ||
        error instanceof UnexpectedResponseError
    );
}

export function correlationIdOf(error: unknown): string | undefined {
    return error instanceof ApiError || error instanceof UnexpectedResponseError ? error.correlationId : undefined;
}
