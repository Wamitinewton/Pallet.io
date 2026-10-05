import { ApiError } from "./errors";

export const TOO_MANY_REQUESTS = "TOO_MANY_REQUESTS";
export const DEFAULT_RETRY_AFTER_SECONDS = 60;

/** What a form can say about a failed request once its endpoint-specific codes have been handled. */
export type RequestFailure =
    | {
          readonly kind: "rate-limited";
          readonly error: ApiError;
          readonly retryAfterSeconds: number;
          readonly retryAt: Date;
      }
    | { readonly kind: "rejected"; readonly error: ApiError }
    | { readonly kind: "unavailable"; readonly error: unknown };

export function classifyRequestFailure(error: unknown, now: Date): RequestFailure {
    if (!(error instanceof ApiError) || error.status >= 500) return { kind: "unavailable", error };
    if (error.code !== TOO_MANY_REQUESTS) return { kind: "rejected", error };

    const retryAfterSeconds = error.retryAfterSeconds() ?? DEFAULT_RETRY_AFTER_SECONDS;
    return {
        kind: "rate-limited",
        error,
        retryAfterSeconds,
        retryAt: new Date(now.getTime() + retryAfterSeconds * 1000),
    };
}

export function secondsUntil(deadline: Date, now: Date): number {
    return Math.max(0, Math.ceil((deadline.getTime() - now.getTime()) / 1000));
}
