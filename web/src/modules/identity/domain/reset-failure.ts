import { ApiError } from "@/shared/domain/errors";
import { classifyRequestFailure, type RequestFailure } from "@/shared/domain/request-failure";
import { INVALID_TOKEN } from "./verification-failure";

/** A missing, used and expired reset token all arrive as `INVALID_TOKEN`; each needs a new link. */
export type ResetFailure = { readonly kind: "expired"; readonly error: ApiError } | RequestFailure;

export function classifyResetFailure(error: unknown, now: Date): ResetFailure {
    if (error instanceof ApiError && error.isClientError() && error.is(INVALID_TOKEN)) {
        return { kind: "expired", error };
    }
    return classifyRequestFailure(error, now);
}
