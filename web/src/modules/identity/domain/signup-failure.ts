import { ApiError } from "@/shared/domain/errors";
import { classifyRequestFailure, type RequestFailure } from "@/shared/domain/request-failure";

export const CONFLICT = "CONFLICT";
export const IDEMPOTENCY_KEY_REUSE = "IDEMPOTENCY_KEY_REUSE";

export type TakenField = "slug" | "email";

/** The backend answers a taken slug and a taken email with the same code; `field` says which, when known. */
export class SignupConflictError extends Error {
    override readonly name = "SignupConflictError";

    constructor(
        readonly field: TakenField | undefined,
        options?: ErrorOptions,
    ) {
        super(field === undefined ? "The slug or the email is taken" : `The ${field} is taken`, options);
    }
}

export function isTakenConflict(error: unknown): error is ApiError {
    return error instanceof ApiError && error.status === 409 && error.is(CONFLICT);
}

export type SignupFailure = { readonly kind: "taken"; readonly field: TakenField | undefined } | RequestFailure;

export function classifySignupFailure(error: unknown, now: Date): SignupFailure {
    if (error instanceof SignupConflictError) return { kind: "taken", field: error.field };
    return classifyRequestFailure(error, now);
}
