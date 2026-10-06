import { asOrgId, asUserId } from "@/shared/domain/ids";
import type { AccountSession, AccountSessionId } from "../../domain/account-session";
import type { PasswordChange } from "../../domain/change-password";
import type { Profile, ProfileUpdate } from "../../domain/profile";
import type { PasswordReset } from "../../domain/reset-password";
import type { SignupDetails, SignupReceipt, SlugAvailability } from "../../domain/signup";
import type { EmailVerification } from "../../domain/verification-code";
import type {
    AccountRepository,
    CancellableRequest,
    PasswordRepository,
    SignupRepository,
    VerificationRepository,
} from "../ports";

export interface RecordedSignup {
    readonly details: SignupDetails;
    readonly idempotencyKey: string;
}

export class ScriptedSignupRepository implements SignupRepository {
    readonly signups: RecordedSignup[] = [];
    readonly checkedSlugs: string[] = [];
    readonly signals: (AbortSignal | undefined)[] = [];
    nextSignUp: (details: SignupDetails) => Promise<SignupReceipt> = (details) =>
        Promise.resolve({ orgId: asOrgId("org-1"), organizationName: details.organizationName, slug: details.slug });
    nextCheck: (slug: string) => Promise<SlugAvailability> = (slug) => Promise.resolve({ slug, available: true });

    signUp(details: SignupDetails, idempotencyKey: string) {
        this.signups.push({ details, idempotencyKey });
        return this.nextSignUp(details);
    }

    checkSlug(slug: string, request?: CancellableRequest) {
        this.checkedSlugs.push(slug);
        this.signals.push(request?.signal);
        return this.nextCheck(slug);
    }
}

export class ScriptedVerificationRepository implements VerificationRepository {
    readonly verified: EmailVerification[] = [];
    readonly resent: string[] = [];
    nextVerify: () => Promise<void> = () => Promise.resolve();
    nextResend: () => Promise<void> = () => Promise.resolve();

    verify(verification: EmailVerification) {
        this.verified.push(verification);
        return this.nextVerify();
    }

    resend(email: string) {
        this.resent.push(email);
        return this.nextResend();
    }
}

export class ScriptedPasswordRepository implements PasswordRepository {
    readonly resetRequests: string[] = [];
    readonly resets: PasswordReset[] = [];
    nextRequestReset: () => Promise<void> = () => Promise.resolve();
    nextReset: () => Promise<void> = () => Promise.resolve();

    requestReset(email: string) {
        this.resetRequests.push(email);
        return this.nextRequestReset();
    }

    reset(reset: PasswordReset) {
        this.resets.push(reset);
        return this.nextReset();
    }
}

export class ScriptedAccountRepository implements AccountRepository {
    readonly profileUpdates: ProfileUpdate[] = [];
    readonly passwordChanges: PasswordChange[] = [];
    readonly revoked: AccountSessionId[] = [];
    otherSessionsRevoked = 0;
    profile: Profile = {
        userId: asUserId("user-1"),
        email: "amani@kilimalabs.co",
        displayName: "Amani Otieno",
        status: "ACTIVE",
    };
    sessions: readonly AccountSession[] = [];
    nextUpdateProfile: () => Promise<void> = () => Promise.resolve();
    nextChangePassword: () => Promise<void> = () => Promise.resolve();
    nextRevokeSession: () => Promise<void> = () => Promise.resolve();
    nextRevokeOtherSessions: () => Promise<void> = () => Promise.resolve();

    getMyProfile() {
        return Promise.resolve(this.profile);
    }

    updateProfile(update: ProfileUpdate) {
        this.profileUpdates.push(update);
        return this.nextUpdateProfile();
    }

    changePassword(change: PasswordChange) {
        this.passwordChanges.push(change);
        return this.nextChangePassword();
    }

    listSessions() {
        return Promise.resolve(this.sessions);
    }

    revokeSession(id: AccountSessionId) {
        this.revoked.push(id);
        return this.nextRevokeSession();
    }

    revokeOtherSessions() {
        this.otherSessionsRevoked++;
        return this.nextRevokeOtherSessions();
    }
}
