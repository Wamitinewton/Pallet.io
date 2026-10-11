import type { AppId, InstallationId, RepoId, UserId } from "@/shared/domain/ids";
import type { IsoInstant } from "@/shared/domain/instant";

export const DISCONNECT_REASONS = [
    "UNLINKED_BY_USER",
    "INSTALLATION_UNLINKED",
    "INSTALLATION_DELETED",
    "REPOSITORY_ACCESS_REMOVED",
    "REPOSITORY_DELETED",
    "APP_DELETED",
    "ORG_DELETED",
    "VERIFIER_ACCESS_LOST",
] as const;

export type DisconnectReason = (typeof DISCONNECT_REASONS)[number];

/** Codes for an active link that still works but needs attention. */
export const REPO_LINK_WARNINGS = ["REPOSITORY_ARCHIVED"] as const;

export type RepoLinkWarning = (typeof REPO_LINK_WARNINGS)[number];

/** A verifier always holds at least push, or the link couldn't have been made. */
export const VERIFIER_PERMISSIONS = ["push", "maintain", "admin"] as const;

export type VerifierPermission = (typeof VERIFIER_PERMISSIONS)[number];

export interface BuildSettings {
    readonly productionBranch: string;
    /** Relative to the repository root, without a trailing `/`; empty is the root itself. */
    readonly rootDirectory: string;
    /** Whether pushes to the production branch build on their own. */
    readonly autoDeploy: boolean;
}

export type Connection =
    | { readonly status: "ACTIVE" }
    | {
          readonly status: "DISCONNECTED";
          readonly reason: DisconnectReason;
          readonly disconnectedAt: IsoInstant;
      };

/** The production branch's head as push processing last accepted it. */
export interface AcceptedHead {
    readonly branch: string;
    readonly sha: string;
    readonly advancedAt: IsoInstant;
    /** The commit's first line, untrusted text; null for a head stored before the service kept it. */
    readonly message: string | null;
    /** The author's GitHub login, else their name; untrusted text. */
    readonly author: string | null;
}

/** Who vouches on GitHub that the link may build this repository, and when that was last confirmed. */
export interface Verification {
    readonly verifiedByUserId: UserId;
    readonly githubLogin: string;
    readonly permission: VerifierPermission;
    readonly accessVerifiedAt: IsoInstant;
    readonly accessCheckedAt: IsoInstant;
}

export interface RepoLink extends BuildSettings {
    readonly appId: AppId;
    readonly installationId: InstallationId;
    readonly repoId: RepoId;
    readonly repoFullName: string;
    readonly connection: Connection;
    readonly warnings: readonly RepoLinkWarning[];
    /** Null before the first accepted push, and once the link is disconnected. */
    readonly lastAcceptedHead: AcceptedHead | null;
    readonly verification: Verification | null;
    /** Sent back on every change, so a save over someone else's is refused. */
    readonly version: number;
    readonly createdAt: IsoInstant;
    readonly updatedAt: IsoInstant;
}

export function isActive(link: RepoLink): boolean {
    return link.connection.status === "ACTIVE";
}

export function isRepoLinkWarning(code: string): code is RepoLinkWarning {
    return (REPO_LINK_WARNINGS as readonly string[]).includes(code);
}

export function repositoryUrl(fullName: string): string {
    const [owner = "", name = ""] = fullName.split("/");
    return `https://github.com/${encodeURIComponent(owner)}/${encodeURIComponent(name)}`;
}

export function commitUrl(fullName: string, sha: string): string {
    return `${repositoryUrl(fullName)}/commit/${encodeURIComponent(sha)}`;
}
