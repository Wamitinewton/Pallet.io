import type { IsoInstant } from "@/shared/domain/instant";
import type { DisconnectReason, RepoLink, RepoLinkWarning } from "./repo-link";

/** What brings a disconnected link back, which decides the action the page offers beside the reason. */
export type Remedy =
    /** Link a repository again: the old one is gone, or nobody can vouch for it any more. */
    | "relink"
    /** The installation left this organization or GitHub: an admin connects it again first. */
    | "reconnect-installation"
    /** The installation no longer reaches the repository: its GitHub owner grants access again. */
    | "restore-access";

/**
 * A reason a live page shows as a fault. An admin unlinking on purpose leaves the app unlinked, not broken, and
 * the app or organization going leaves no page to show anything on.
 */
export type LiveDisconnectReason = Exclude<DisconnectReason, "UNLINKED_BY_USER" | "APP_DELETED" | "ORG_DELETED">;

/** The repository an admin unlinked, still worth naming beside the way to link one again. */
export interface UnlinkedRepository {
    readonly repoFullName: string;
    readonly unlinkedAt: IsoInstant;
}

/** The `data-states` of `app.html`'s repository tab. */
export type LinkHealth =
    | { readonly kind: "unlinked"; readonly previous?: UnlinkedRepository }
    | { readonly kind: "connected"; readonly link: RepoLink }
    | {
          readonly kind: "connected-with-warnings";
          readonly link: RepoLink;
          readonly warnings: readonly [RepoLinkWarning, ...RepoLinkWarning[]];
      }
    | {
          readonly kind: "disconnected";
          readonly link: RepoLink;
          readonly reason: LiveDisconnectReason;
          readonly disconnectedAt: IsoInstant;
          readonly remedy: Remedy;
      };

function remedyFor(reason: LiveDisconnectReason): Remedy {
    switch (reason) {
        case "REPOSITORY_DELETED":
        case "VERIFIER_ACCESS_LOST":
            return "relink";
        case "INSTALLATION_UNLINKED":
        case "INSTALLATION_DELETED":
            return "reconnect-installation";
        case "REPOSITORY_ACCESS_REMOVED":
            return "restore-access";
    }
}

function liveReason(reason: DisconnectReason): LiveDisconnectReason | undefined {
    switch (reason) {
        case "UNLINKED_BY_USER":
        case "APP_DELETED":
        case "ORG_DELETED":
            return undefined;
        case "INSTALLATION_UNLINKED":
        case "INSTALLATION_DELETED":
        case "REPOSITORY_ACCESS_REMOVED":
        case "REPOSITORY_DELETED":
        case "VERIFIER_ACCESS_LOST":
            return reason;
    }
}

/** Only for a link that was read: a failed read is an error to show, never a disconnected link. */
export function linkHealth(link: RepoLink | null): LinkHealth {
    if (link === null) return { kind: "unlinked" };
    const { connection } = link;
    if (connection.status === "DISCONNECTED") {
        if (connection.reason === "UNLINKED_BY_USER") {
            return {
                kind: "unlinked",
                previous: { repoFullName: link.repoFullName, unlinkedAt: connection.disconnectedAt },
            };
        }
        const reason = liveReason(connection.reason);
        if (reason === undefined) return { kind: "unlinked" };
        return {
            kind: "disconnected",
            link,
            reason,
            disconnectedAt: connection.disconnectedAt,
            remedy: remedyFor(reason),
        };
    }
    const [first, ...rest] = link.warnings;
    return first === undefined
        ? { kind: "connected", link }
        : { kind: "connected-with-warnings", link, warnings: [first, ...rest] };
}
