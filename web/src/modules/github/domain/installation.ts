import type { InstallationId, UserId } from "@/shared/domain/ids";
import { asInstallationId } from "@/shared/domain/ids";
import type { IsoInstant } from "@/shared/domain/instant";

export const ACCOUNT_TYPES = ["User", "Organization"] as const;

export type GitHubAccountType = (typeof ACCOUNT_TYPES)[number];

export const INSTALLATION_STATUSES = ["ACTIVE", "SUSPENDED"] as const;

export type InstallationStatus = (typeof INSTALLATION_STATUSES)[number];

/** An installation linked to an organization. */
export interface InstallationLink {
    readonly installationId: InstallationId;
    readonly accountLogin: string;
    readonly accountType: GitHubAccountType;
    readonly status: InstallationStatus;
    readonly linkedByUserId: UserId;
    readonly linkedAt: IsoInstant;
}

/** An installation the caller's GitHub user can see, linked anywhere or nowhere. */
export interface VisibleInstallation {
    readonly installationId: InstallationId;
    readonly accountLogin: string;
    readonly accountType: GitHubAccountType;
    readonly suspended: boolean;
}

export interface AuthorizationGrant {
    readonly code: string;
    readonly state: string;
}

/** A fresh install brings its own grant from GitHub's setup redirect; an existing one needs a GitHub session. */
export interface LinkRequest {
    readonly installationId: InstallationId;
    readonly grant?: AuthorizationGrant;
}

export interface LinkOutcome {
    readonly link: InstallationLink;
    /** The organization had it linked already, so nothing changed. */
    readonly alreadyLinked: boolean;
}

export type Linkability = "available" | "linkedHere" | "suspended";

export interface LinkableInstallation {
    readonly installation: VisibleInstallation;
    readonly linkability: Linkability;
}

function linkabilityOf(installation: VisibleInstallation, linkedHere: ReadonlySet<InstallationId>): Linkability {
    if (installation.suspended) return "suspended";
    return linkedHere.has(installation.installationId) ? "linkedHere" : "available";
}

/** Linked here is marked but still offered, since the list it was read from may be stale; suspended isn't. */
export function linkableInstallations(
    visible: readonly VisibleInstallation[],
    linkedHere: ReadonlySet<InstallationId>,
): readonly LinkableInstallation[] {
    return visible.map((installation) => ({ installation, linkability: linkabilityOf(installation, linkedHere) }));
}

const POSITIVE_INTEGER = /^[1-9]\d*$/;

/** An installation id from untrusted text: a positive integer within the safe range, nothing else. */
export function installationIdFrom(value: string | null | undefined): InstallationId | undefined {
    if (value == null || !POSITIVE_INTEGER.test(value)) return undefined;
    const id = Number(value);
    return Number.isSafeInteger(id) ? asInstallationId(id) : undefined;
}

/** Where the account's owner chooses which repositories the app sees, or unsuspends it. */
export function installationSettingsUrl({
    installationId,
    accountLogin,
    accountType,
}: Pick<InstallationLink, "installationId" | "accountLogin" | "accountType">): string {
    const id = String(installationId);
    return accountType === "Organization"
        ? `https://github.com/organizations/${encodeURIComponent(accountLogin)}/settings/installations/${id}`
        : `https://github.com/settings/installations/${id}`;
}

export function avatarUrl(login: string): string {
    return `https://github.com/${encodeURIComponent(login)}.png`;
}
