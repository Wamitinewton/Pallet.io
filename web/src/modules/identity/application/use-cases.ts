import { makeChangePassword, type ChangePassword } from "./change-password";
import { makeCheckSlugAvailability, type CheckSlugAvailability } from "./check-slug-availability";
import { makeGetMyProfile, type GetMyProfile } from "./get-my-profile";
import { makeListSessions, type ListSessions } from "./list-sessions";
import type { AccountRepository, PasswordRepository, SignupRepository, VerificationRepository } from "./ports";
import { makeRequestPasswordReset, type RequestPasswordReset } from "./request-password-reset";
import { makeResendVerification, type ResendVerification } from "./resend-verification";
import { makeResetPassword, type ResetPassword } from "./reset-password";
import { makeRevokeOtherSessions, type RevokeOtherSessions } from "./revoke-other-sessions";
import { makeRevokeSession, type RevokeSession } from "./revoke-session";
import { makeSignUp, type SignUp } from "./sign-up";
import { makeUpdateProfile, type UpdateProfile } from "./update-profile";
import { makeVerifyEmail, type VerifyEmail } from "./verify-email";

export interface IdentityUseCases {
    readonly signUp: SignUp;
    readonly checkSlugAvailability: CheckSlugAvailability;
    readonly verifyEmail: VerifyEmail;
    readonly resendVerification: ResendVerification;
    readonly requestPasswordReset: RequestPasswordReset;
    readonly resetPassword: ResetPassword;
    readonly getMyProfile: GetMyProfile;
    readonly updateProfile: UpdateProfile;
    readonly changePassword: ChangePassword;
    readonly listSessions: ListSessions;
    readonly revokeSession: RevokeSession;
    readonly revokeOtherSessions: RevokeOtherSessions;
}

export interface IdentityDependencies {
    readonly signups: SignupRepository;
    readonly verifications: VerificationRepository;
    readonly passwords: PasswordRepository;
    readonly accounts: AccountRepository;
}

export function makeIdentityUseCases({
    signups,
    verifications,
    passwords,
    accounts,
}: IdentityDependencies): IdentityUseCases {
    return {
        signUp: makeSignUp(signups),
        checkSlugAvailability: makeCheckSlugAvailability(signups),
        verifyEmail: makeVerifyEmail(verifications),
        resendVerification: makeResendVerification(verifications),
        requestPasswordReset: makeRequestPasswordReset(passwords),
        resetPassword: makeResetPassword(passwords),
        getMyProfile: makeGetMyProfile(accounts),
        updateProfile: makeUpdateProfile(accounts),
        changePassword: makeChangePassword(accounts),
        listSessions: makeListSessions(accounts),
        revokeSession: makeRevokeSession(accounts),
        revokeOtherSessions: makeRevokeOtherSessions(accounts),
    };
}
