import { makeCheckSlugAvailability, type CheckSlugAvailability } from "./check-slug-availability";
import type { PasswordRepository, SignupRepository, VerificationRepository } from "./ports";
import { makeRequestPasswordReset, type RequestPasswordReset } from "./request-password-reset";
import { makeResendVerification, type ResendVerification } from "./resend-verification";
import { makeResetPassword, type ResetPassword } from "./reset-password";
import { makeSignUp, type SignUp } from "./sign-up";
import { makeVerifyEmail, type VerifyEmail } from "./verify-email";

export interface IdentityUseCases {
    readonly signUp: SignUp;
    readonly checkSlugAvailability: CheckSlugAvailability;
    readonly verifyEmail: VerifyEmail;
    readonly resendVerification: ResendVerification;
    readonly requestPasswordReset: RequestPasswordReset;
    readonly resetPassword: ResetPassword;
}

export interface IdentityDependencies {
    readonly signups: SignupRepository;
    readonly verifications: VerificationRepository;
    readonly passwords: PasswordRepository;
}

export function makeIdentityUseCases({ signups, verifications, passwords }: IdentityDependencies): IdentityUseCases {
    return {
        signUp: makeSignUp(signups),
        checkSlugAvailability: makeCheckSlugAvailability(signups),
        verifyEmail: makeVerifyEmail(verifications),
        resendVerification: makeResendVerification(verifications),
        requestPasswordReset: makeRequestPasswordReset(passwords),
        resetPassword: makeResetPassword(passwords),
    };
}
