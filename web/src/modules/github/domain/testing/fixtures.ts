import { asInstallationId, asUserId } from "@/shared/domain/ids";
import { asIsoInstant } from "@/shared/domain/instant";
import type { GitHubSession } from "../github-session";
import type { InstallationLink, VisibleInstallation } from "../installation";
import type { GitHubHandoff } from "../return-intent";

export const KILIMA_LABS_INSTALLATION = asInstallationId(41000001);
export const AMANI_INSTALLATION = asInstallationId(41000002);

export function aGitHubSession(overrides: Partial<GitHubSession> = {}): GitHubSession {
    return { githubLogin: "amani-otieno", expiresAt: asIsoInstant("2026-01-15T13:00:00Z"), ...overrides };
}

export function anInstallationLink(overrides: Partial<InstallationLink> = {}): InstallationLink {
    return {
        installationId: KILIMA_LABS_INSTALLATION,
        accountLogin: "kilima-labs",
        accountType: "Organization",
        status: "ACTIVE",
        linkedByUserId: asUserId("user-amani"),
        linkedAt: asIsoInstant("2026-01-10T09:00:00Z"),
        ...overrides,
    };
}

export function aVisibleInstallation(overrides: Partial<VisibleInstallation> = {}): VisibleInstallation {
    return {
        installationId: KILIMA_LABS_INSTALLATION,
        accountLogin: "kilima-labs",
        accountType: "Organization",
        suspended: false,
        ...overrides,
    };
}

export function aHandoff(url: string): GitHubHandoff {
    return { url, expiresAt: asIsoInstant("2026-01-15T12:10:00Z") };
}
