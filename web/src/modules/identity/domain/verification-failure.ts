import { ApiError } from "@/shared/domain/errors";
import { classifyRequestFailure, type RequestFailure } from "@/shared/domain/request-failure";

/** A wrong code, an expired one and a spent attempt budget all arrive as this one code. */
export const INVALID_TOKEN = "INVALID_TOKEN";

export type VerificationFailure = { readonly kind: "wrong-code"; readonly error: ApiError } | RequestFailure;

export function classifyVerificationFailure(error: unknown, now: Date): VerificationFailure {
    if (error instanceof ApiError && error.isClientError() && error.is(INVALID_TOKEN)) {
        return { kind: "wrong-code", error };
    }
    return classifyRequestFailure(error, now);
}
