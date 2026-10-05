import { ApiError } from "@/shared/domain/errors";
import { classifyRequestFailure, type RequestFailure } from "@/shared/domain/request-failure";

export const INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
export const EMAIL_NOT_VERIFIED = "EMAIL_NOT_VERIFIED";

export type SignInFailure =
    | { readonly kind: "invalid-credentials"; readonly error: ApiError }
    | { readonly kind: "email-not-verified"; readonly error: ApiError }
    | RequestFailure;

export function classifySignInFailure(error: unknown, now: Date): SignInFailure {
    if (error instanceof ApiError && error.isClientError()) {
        if (error.is(INVALID_CREDENTIALS)) return { kind: "invalid-credentials", error };
        if (error.is(EMAIL_NOT_VERIFIED)) return { kind: "email-not-verified", error };
    }
    return classifyRequestFailure(error, now);
}
