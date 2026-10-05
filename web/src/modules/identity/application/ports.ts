import type { PasswordReset } from "../domain/reset-password";
import type { SignupDetails, SignupReceipt, SlugAvailability } from "../domain/signup";
import type { EmailVerification } from "../domain/verification-code";

export interface CancellableRequest {
    readonly signal?: AbortSignal | undefined;
}

export interface SignupRepository {
    signUp(details: SignupDetails, idempotencyKey: string): Promise<SignupReceipt>;
    checkSlug(slug: string, request?: CancellableRequest): Promise<SlugAvailability>;
}

export interface VerificationRepository {
    verify(verification: EmailVerification): Promise<void>;
    /** Succeeds whether or not an account exists for `email`; the backend never says which. */
    resend(email: string): Promise<void>;
}

export interface PasswordRepository {
    /** Succeeds whether or not an account exists for `email`; the backend never says which. */
    requestReset(email: string): Promise<void>;
    reset(reset: PasswordReset): Promise<void>;
}
