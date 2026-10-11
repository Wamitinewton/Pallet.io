import type { OrgKind } from "@/modules/organizations";
import type { LiveDisconnectReason } from "@/modules/repo-links";

export const OVERVIEW_TITLE_COPY = "Overview";
export const NEW_APP_COPY = "New app";

const KIND_COPY: Readonly<Record<OrgKind, string>> = {
    PERSONAL: "Personal",
    TEAM: "Team",
};

const createdDate = new Intl.DateTimeFormat("en-GB", { dateStyle: "long", timeZone: "UTC" });

export function organizationSummaryCopy({
    kind,
    slug,
    createdAt,
}: {
    readonly kind: OrgKind;
    readonly slug: string;
    readonly createdAt: string;
}): { readonly lead: string; readonly address: string; readonly created: string } {
    return {
        lead: `${KIND_COPY[kind]} organization at`,
        address: `pallet.dev/${slug}`,
        created: `Created ${createdDate.format(new Date(createdAt))}.`,
    };
}

export const STAT_LABEL = {
    apps: "Apps",
    members: "Members",
    teams: "Teams",
} as const;

/** The number and its label read as one phrase, which the stat's two stacked spans don't on their own. */
export function statLinkLabel(count: number, label: string): string {
    return `${String(count)} ${label.toLowerCase()}`;
}

export const LOADING_COUNTS_COPY = "Loading the organization's counts";
export const COUNTS_UNAVAILABLE_COPY = "Couldn't load the counts";

export const NEWEST_APPS_TITLE_COPY = "Newest apps";
export const VIEW_ALL_COPY = "View all";
export const VIEW_ALL_APPS_LABEL_COPY = "View all apps";
export const LOADING_APPS_COPY = "Loading the newest apps";
export const APPS_UNAVAILABLE_COPY = "Couldn't load the apps";
export const NO_APPS_TITLE_COPY = "No apps yet";
export const NO_APPS_CREATOR_COPY = "Create an app to choose where it runs, then link the repository it builds from.";
export const NO_APPS_COPY = "A developer or above can create the first one.";

export const CHECKING_COPY = "Checking…";
export const UNAVAILABLE_COPY = "Couldn't check";
export const NO_REPOSITORY_COPY = "No repository";
export const LINK_TO_BUILD_COPY = "Link a repository to start building this app";
export const NO_PUSH_YET_COPY = "No push picked up yet";
export const LOST_ACCESS_COPY = "Pallet lost access";
export const ON_COPY = "on";
export const ARCHIVED_COPY = "Archived on GitHub";

export const ATTENTION_TITLE_COPY = "Needs your attention";
export const CHECKING_ATTENTION_COPY = "Checking what needs your attention";
export const ATTENTION_UNAVAILABLE_COPY = "Couldn't check what needs your attention";
export const CALM_TITLE_COPY = "Nothing needs your attention";
export const CALM_COPY = "Repositories, GitHub access and invites all look fine.";
export const INCOMPLETE_COPY = "Some checks couldn't run, so this list may be missing something.";
export const TRY_AGAIN_COPY = "Try again";

export function attentionCountLabel(count: number): string {
    return `${String(count)} ${count === 1 ? "item" : "items"}`;
}

const LOST_REASON_COPY: Readonly<Record<LiveDisconnectReason, string>> = {
    INSTALLATION_UNLINKED: "the GitHub installation it came through was removed from this organization",
    INSTALLATION_DELETED: "the Pallet GitHub App was uninstalled from the account that owns it",
    REPOSITORY_ACCESS_REMOVED: "the Pallet GitHub App no longer has access to it",
    REPOSITORY_DELETED: "it was deleted on GitHub",
    VERIFIER_ACCESS_LOST: "nobody who vouched for it can push to it any more",
};

export function disconnectedTitleCopy(appName: string, reason: LiveDisconnectReason): string {
    return `${appName} lost its repository: ${LOST_REASON_COPY[reason]}`;
}

export function disconnectedDescriptionCopy(repoFullName: string): string {
    return `Pushes to ${repoFullName} won't build until the link is fixed.`;
}

export const FIX_LINK_COPY = "Fix the link";

export function archivedTitleCopy(appName: string): string {
    return `${appName}'s repository is archived`;
}

export function archivedDescriptionCopy(repoFullName: string): string {
    return `${repoFullName} is read-only on GitHub, so there won't be new pushes to build.`;
}

export const REVIEW_LINK_COPY = "Review the link";

export function suspendedTitleCopy(accountLogin: string): string {
    return `GitHub access for ${accountLogin} is suspended`;
}

export const SUSPENDED_COPY =
    "Builds from its repositories can't start until its owner unsuspends the Pallet GitHub App.";
export const REVIEW_INSTALLATIONS_COPY = "Review installations";

export const CONNECT_GITHUB_TITLE_COPY = "Connect GitHub to link repositories";
export const CONNECT_GITHUB_COPY = "Install the Pallet GitHub App on the account that owns your code.";
export const CONNECT_GITHUB_ACTION_COPY = "Connect GitHub";

export const LINK_REPOSITORY_TITLE_COPY = "Link a repository to start building";

export function linkRepositoryDescriptionCopy(appName: string): string {
    return `None of the newest apps builds from a repository yet. Start with ${appName}.`;
}

export function openAppCopy(appName: string): string {
    return `Open ${appName}`;
}

export function pendingInvitesTitleCopy(count: number): string {
    return count === 1 ? "1 invite is waiting to be accepted" : `${String(count)} invites are waiting to be accepted`;
}

export const PENDING_INVITES_COPY = "Resend or revoke them from the invites tab.";
export const SEE_INVITES_COPY = "See invites";

export function unreadTitleCopy(count: number): string {
    return `${String(count)} unread ${count === 1 ? "notification" : "notifications"}`;
}

export const UNREAD_COPY = "Across every organization you belong to.";
export const READ_NOTIFICATIONS_COPY = "Read them";
