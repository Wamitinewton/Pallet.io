import type { ErrorCopy } from "@/shared/presentation/errors";
import type { BuildSettingField } from "../domain/build-settings";
import type { LiveDisconnectReason } from "../domain/link-health";
import type { BlockedReason, RepositoryPermission } from "../domain/picker-repository";
import {
    BRANCH_NOT_FOUND,
    CONCURRENT_MODIFICATION,
    GITHUB_AUTHORIZATION_REQUIRED,
    GITHUB_RATE_LIMITED,
    IDEMPOTENCY_KEY_REUSE,
    INSTALLATION_NOT_ACCESSIBLE,
    INSTALLATION_NOT_FOUND,
    INSTALLATION_SUSPENDED,
    INSUFFICIENT_ROLE,
    REPO_LINK_EXISTS,
    REPO_LINK_NOT_FOUND,
    REPOSITORY_ARCHIVED,
    REPOSITORY_NOT_ACCESSIBLE,
    REPOSITORY_PERMISSION_TOO_LOW,
} from "../domain/repo-link-errors";

export const REPOSITORY_LABEL_COPY = "Repository";
export const APPS_COPY = "Apps";
export const LOADING_LINK_COPY = "Loading the repository link";
export const LINK_A_REPOSITORY_COPY = "Link a repository";
export const LINK_AGAIN_COPY = "Link again";
export const TRY_AGAIN_COPY = "Try again";
export const OPEN_GITHUB_SETTINGS_COPY = "Open GitHub settings";
export const UPDATE_ACCESS_ON_GITHUB_COPY = "Update access on GitHub";

export const UNLINKED_TITLE_COPY = "Link a repository to start building";
export const UNLINKED_COPY =
    "Pick a GitHub repository and the branch that counts as production. Every push to that branch will start a build.";
export const UNLINKED_ADMIN_HINT_COPY = "Needs an admin, and push access to the repository on GitHub.";
export const NO_REPOSITORY_TITLE_COPY = "No repository yet";
export const NO_REPOSITORY_COPY = "An admin can link the GitHub repository this app builds from.";

export const STATUS_LABEL = {
    connected: "Connected",
    disconnected: "Disconnected",
    checking: "Checking…",
} as const;

export const UNAVAILABLE_BADGE_COPY = "Couldn't check";
export const LINK_REPOSITORY_SHORT_COPY = "Link repository";

export function linkRepositoryForCopy(appName: string): string {
    return `Link a repository to ${appName}`;
}

export function throughInstallationCopy(accountLogin: string | undefined): string {
    return accountLogin === undefined
        ? "Through the organization's GitHub installation"
        : `Through the ${accountLogin} GitHub installation`;
}

export const LATEST_PUSH_COPY = "Latest push Pallet picked up";
export const NO_PUSH_YET_COPY = "No push picked up yet. The next push to the production branch shows up here.";
export const COPY_SHA_LABEL_COPY = "commit SHA";
export const ON_COPY = "on";
export const ARCHIVED_TITLE_COPY = "This repository is archived on GitHub.";
export const ARCHIVED_COPY =
    "The link still works, but archived repositories are read-only, so there won't be new pushes to build. Unarchive it on GitHub, or link the repository that replaced it.";

export function disconnectedTitleCopy(repoFullName: string): string {
    return `Pallet can't build from ${repoFullName}.`;
}

export function previouslyLinkedCopy(repoFullName: string): string {
    return `${repoFullName} was unlinked`;
}

export function disconnectReasonCopy(
    reason: LiveDisconnectReason,
    { orgName, verifierLogin }: { readonly orgName: string; readonly verifierLogin: string | undefined },
): string {
    switch (reason) {
        case "INSTALLATION_UNLINKED":
            return `The GitHub installation it came through was removed from ${orgName}. Pushes won't build until that account is connected again and the repository is linked again.`;
        case "INSTALLATION_DELETED":
            return "The Pallet GitHub App was uninstalled from the account that owns it. Pushes won't build until the app is installed again and the repository is linked again.";
        case "REPOSITORY_ACCESS_REMOVED":
            return "The Pallet GitHub App no longer has access to it. This usually means someone removed it from the app's repository list on GitHub, or it was renamed or moved. Pushes won't build until this is fixed.";
        case "REPOSITORY_DELETED":
            return "It was deleted on GitHub. Link the repository that replaced it to build again.";
        case "VERIFIER_ACCESS_LOST":
            return `${verifierLogin === undefined ? "The person who vouched for it" : `@${verifierLogin}, who vouched for it,`} no longer has push access on GitHub, so Pallet stopped trusting the link. An admin with push access needs to link it again.`;
    }
}

export const DISCONNECTED_ON_COPY = "Disconnected";
export const ADMIN_CAN_RELINK_COPY = "An admin can link it again.";

export const BUILD_SETTINGS_TITLE_COPY = "Build settings";
export const BUILD_SETTINGS_DESCRIPTION_COPY = "Developers and above can change these.";
export const BUILD_SETTINGS_READ_ONLY_COPY = "Only developers and above can change these.";
export const BUILD_SETTINGS_DISCONNECTED_COPY = "Link a repository again to change these.";
export const PRODUCTION_BRANCH_LABEL_COPY = "Production branch";
export const PRODUCTION_BRANCH_HINT_COPY = "Pushes to this branch start builds.";
export const DEFAULT_BRANCH_HINT_COPY = "Filled in from the repository's default branch.";
export const ROOT_DIRECTORY_LABEL_COPY = "Root directory";
export const ROOT_DIRECTORY_PLACEHOLDER_COPY = "Repository root";
export const ROOT_DIRECTORY_HINT_COPY = "Where the app lives, for monorepos. Leave it empty to build from the root.";
export const AUTO_DEPLOY_LABEL_COPY = "Build every push";
export const AUTO_DEPLOY_HINT_COPY = "Turn this off to start builds by hand only.";
export const SAVE_CHANGES_COPY = "Save changes";
export const SETTINGS_SAVED_COPY = "Build settings saved";
export const SETTINGS_NOT_SAVED_COPY = "The build settings weren't saved.";
export const LINK_GONE_COPY = "This repository was unlinked meanwhile.";

export const SETTING_LABEL: Readonly<Record<BuildSettingField, string>> = {
    productionBranch: "production branch",
    rootDirectory: "root directory",
    autoDeploy: "building every push",
};

export function changedMeanwhileCopy(fields: readonly BuildSettingField[]): string {
    const what =
        fields.length === 0 ? "these build settings" : `the ${listCopy(fields.map((field) => SETTING_LABEL[field]))}`;
    return `Someone else changed ${what} while you were editing. Your edits are still here: check them against the latest values and save again.`;
}

export function latestValueCopy(field: BuildSettingField, value: string | boolean): string {
    if (field === "autoDeploy") return value === true ? "Now on" : "Now off";
    if (field === "rootDirectory" && value === "") return "Now the repository root";
    return `Now ${String(value)}`;
}

export function branchNotFoundCopy(branch: string): string {
    return `There's no branch named ${branch} on GitHub.`;
}

export const ACCESS_TITLE_COPY = "GitHub access";
export const ADMINS_ONLY_COPY = "Admins only";
export const ACCESS_CONFIRMED_LABEL_COPY = "Access confirmed";
export const LAST_CHECKED_LABEL_COPY = "Last checked";
export const CHECK_FAILED_COPY = "Failed";
export const NOBODY_VOUCHES_COPY = "Nobody vouches for this link yet.";
export const ACCESS_EXPLANATION_COPY =
    "Pallet checks every day that the person who linked the repository can still push to it. If they lose access, the link stops building, so hand it over before they leave the GitHub organization.";
export const TAKE_OVER_COPY = "Take over verification";
export const TAKE_OVER_SIGN_IN_COPY = "You'll sign in with GitHub first, then come back here.";
export const FORMER_MEMBER_COPY = "a former member";

export function vouchedAsCopy(githubLogin: string, permission: string): string {
    return `As @${githubLogin}, who has ${permission} access to this repository.`;
}

const confirmedDate = new Intl.DateTimeFormat("en-GB", { dateStyle: "long", timeZone: "UTC" });

export function confirmedOnCopy(instant: string): string {
    return confirmedDate.format(new Date(instant));
}

export function vouchedByCopy(name: string | undefined): string {
    return name === undefined ? "Linked on GitHub" : `Linked by ${name}`;
}

export const PERMISSION_LABEL: Readonly<Record<RepositoryPermission, string>> = {
    admin: "admin",
    maintain: "maintain",
    push: "push",
    triage: "triage",
    pull: "read",
};

export function tookOverCopy(repoFullName: string): string {
    return `You now vouch for ${repoFullName}`;
}

export const DISCONNECT_TITLE_COPY = "Unlink repository";
export const DISCONNECT_DESCRIPTION_COPY =
    "Pushes stop building this app. The app and its settings stay, and you can link a repository again at any time.";
export const KEEP_LINKED_COPY = "Keep it linked";

export function disconnectConfirmTitle(repoFullName: string): string {
    return `Unlink ${repoFullName}?`;
}

export function disconnectConfirmDescription(productionBranch: string, appName: string): string {
    return `Pushes to ${productionBranch} will stop building ${appName}. Nothing changes on GitHub.`;
}

export const DISCONNECTED_TOAST_COPY = "Repository unlinked";
export const ALREADY_UNLINKED_COPY = "It was already unlinked.";

export const START_BUILD_COPY = "Start a build";
export const START_BUILD_SUBMIT_COPY = "Start build";
export const START_BUILD_DESCRIPTION_COPY = "Pallet builds the latest commit on the branch you choose.";
export const BUILD_BRANCH_LABEL_COPY = "Branch";
export const BUILD_LIMIT_COPY = "Each app can start a limited number of builds an hour.";
export const CANCEL_COPY = "Cancel";

export function buildBranchHintCopy(productionBranch: string): string {
    return `Leave it as ${productionBranch} to build production. Any other branch works too.`;
}

export function buildRequestedCopy(sha: string, branch: string): string {
    return `Build requested for ${sha.slice(0, 7)} on ${branch}`;
}

export function buildLimitReachedCopy(seconds: number): string {
    return `This app has started as many builds as it can this hour. Try again in ${waitCopy(seconds)}.`;
}

export function retryInCopy(seconds: number): string {
    return `Try again in ${waitCopy(seconds)}`;
}

function waitCopy(seconds: number): string {
    if (seconds < 60) return `${String(seconds)} second${seconds === 1 ? "" : "s"}`;
    const minutes = Math.ceil(seconds / 60);
    return `${String(minutes)} minute${minutes === 1 ? "" : "s"}`;
}

export const SUSPENDED_COPY =
    "The Pallet GitHub App is suspended on the account that owns this repository, so builds can't start. Its owner can unsuspend it on GitHub.";

export function connectTitleCopy(appName: string): string {
    return `Link a repository to ${appName}`;
}

export function connectDescriptionCopy(appName: string): string {
    return `Pushes to the branch you pick here will start builds for ${appName}. You need push access to the repository on GitHub.`;
}

export const LOADING_CONNECT_COPY = "Loading";
export const STEP_SESSION_TITLE_COPY = "Confirm who you are on GitHub";
export const STEP_SESSION_DONE_TITLE_COPY = "GitHub account";
export const STEP_SESSION_COPY =
    "Pallet needs to know your GitHub account so it can show the repositories you can push to. It only reads your repository access, and the sign-in lasts up to an hour.";
export const CONTINUE_WITH_GITHUB_COPY = "Continue with GitHub";
export const SESSION_UNAVAILABLE_COPY = "Your GitHub sign-in couldn't be checked.";
export const SESSION_ENDED_COPY = "Your GitHub sign-in ended. Sign in again to continue.";
export const STEP_REPOSITORY_TITLE_COPY = "Choose a repository";
export const STEP_SETTINGS_TITLE_COPY = "Build settings";
export const CHOOSE_REPOSITORY_FIRST_COPY = "Choose a repository first.";

export function signedInAsCopy(login: string): string {
    return `Signed in as @${login}`;
}

export const INSTALLATION_LABEL_COPY = "GitHub account";
export const LOADING_INSTALLATIONS_COPY = "Loading GitHub accounts";
export const INSTALLATIONS_UNAVAILABLE_COPY = "The organization's GitHub accounts couldn't be loaded.";
export const NO_INSTALLATIONS_TITLE_COPY = "GitHub isn't connected yet";
export const NO_INSTALLATIONS_COPY =
    "Install the Pallet GitHub App on the GitHub account that owns the code, then come back to pick a repository.";
export const CONNECT_GITHUB_COPY = "Connect GitHub";
export const MISSING_ACCOUNT_COPY = "Don't see the account you need?";
export const INSTALL_THERE_COPY = "Install the Pallet GitHub App there";
export const SUSPENDED_SUFFIX_COPY = "suspended";

export const ACCOUNT_KIND = {
    Organization: "organization",
    User: "personal account",
} as const;

export const REPOSITORIES_LABEL_COPY = "Repositories";
export const FILTER_REPOSITORIES_COPY = "Filter repositories";
export const FILTER_PLACEHOLDER_COPY = "Filter by name";
export const LOADING_REPOSITORIES_COPY = "Loading repositories";
export const PICKER_SCOPE_COPY =
    "Only repositories your own GitHub user can reach through this account are listed, which may be fewer than the installation has.";
export const NO_REPOSITORIES_COPY =
    "Your GitHub user can't reach any repository through this account. Ask its owner for access on GitHub, or choose another account.";
export const NO_MATCHING_REPOSITORIES_COPY = "No repository you can reach starts with that name.";
export const PRIVATE_COPY = "Private";
export const PUBLIC_COPY = "Public";
export const ARCHIVED_BADGE_COPY = "Archived";

export const BLOCKED_REASON_COPY: Readonly<Record<BlockedReason, string>> = {
    archived: "Archived repositories can't be linked",
    permission: "Needs push access",
    unavailable: "Can't be linked",
};

export function yourPermissionCopy(permission: RepositoryPermission | null): string {
    return permission === null ? "You: no access" : `You: ${PERMISSION_LABEL[permission]}`;
}

export const PUSH_ACCESS_CHECKED_COPY = "Pallet checks your push access before saving.";
export const LINK_ONLY_COPY = "Link only";
export const LINK_AND_DEPLOY_COPY = "Link and deploy";

export function linkedCopy(repoFullName: string, appName: string): string {
    return `${repoFullName} linked to ${appName}`;
}

export const ALREADY_LINKED_COPY = "This app already has a repository. Here it is.";

const GITHUB_BUSY_COPY = "GitHub is rate limiting Pallet right now. Try again in a few minutes.";

export const linkErrorCopy: ErrorCopy = {
    [REPOSITORY_PERMISSION_TOO_LOW]:
        "You need push access to that repository on GitHub to link it. Ask its owner for access, or choose another.",
    [REPOSITORY_NOT_ACCESSIBLE]:
        "Pallet can't reach that repository through this account any more. Choose another, or check the installation's repository access on GitHub.",
    [REPOSITORY_ARCHIVED]: "That repository is archived on GitHub, so it can't be linked. Choose another.",
    [INSTALLATION_SUSPENDED]:
        "The Pallet GitHub App is suspended on that account. Its owner can unsuspend it on GitHub.",
    [INSTALLATION_NOT_FOUND]: "That GitHub account is no longer connected to this organization. Choose another.",
    [INSTALLATION_NOT_ACCESSIBLE]:
        "Your GitHub user can't see this account's installation. Ask the account's owner on GitHub for access, or choose another.",
    [GITHUB_AUTHORIZATION_REQUIRED]: SESSION_ENDED_COPY,
    [GITHUB_RATE_LIMITED]: GITHUB_BUSY_COPY,
    [REPO_LINK_EXISTS]: ALREADY_LINKED_COPY,
    [INSUFFICIENT_ROLE]: "Only admins can link a repository.",
};

export const repositoriesErrorCopy: ErrorCopy = {
    [INSTALLATION_NOT_ACCESSIBLE]: linkErrorCopy[INSTALLATION_NOT_ACCESSIBLE],
    [INSTALLATION_NOT_FOUND]: linkErrorCopy[INSTALLATION_NOT_FOUND],
    [GITHUB_RATE_LIMITED]: GITHUB_BUSY_COPY,
};

export const tuneErrorCopy: ErrorCopy = {
    [REPO_LINK_NOT_FOUND]: LINK_GONE_COPY,
    [CONCURRENT_MODIFICATION]: changedMeanwhileCopy([]),
    [GITHUB_RATE_LIMITED]: GITHUB_BUSY_COPY,
    [INSUFFICIENT_ROLE]: "Only developers and above can change the build settings.",
};

export const takeOverErrorCopy: ErrorCopy = {
    [REPOSITORY_PERMISSION_TOO_LOW]:
        "Your GitHub user has less than push access to this repository, so it can't vouch for the link. Nothing changed.",
    [REPOSITORY_NOT_ACCESSIBLE]:
        "Your GitHub user can't reach this repository, so it can't vouch for the link. Nothing changed.",
    [REPOSITORY_ARCHIVED]: "The repository is archived on GitHub, so nobody new can vouch for it.",
    [BRANCH_NOT_FOUND]: "The production branch no longer exists on GitHub. Change it in the build settings first.",
    [INSTALLATION_SUSPENDED]: linkErrorCopy[INSTALLATION_SUSPENDED],
    [CONCURRENT_MODIFICATION]: "The link changed while your access was checked. Try again.",
    [REPO_LINK_NOT_FOUND]: LINK_GONE_COPY,
    [GITHUB_AUTHORIZATION_REQUIRED]: SESSION_ENDED_COPY,
    [GITHUB_RATE_LIMITED]: GITHUB_BUSY_COPY,
    [INSUFFICIENT_ROLE]: "Only admins can take over verification.",
};

export const disconnectErrorCopy: ErrorCopy = {
    [REPO_LINK_NOT_FOUND]: ALREADY_UNLINKED_COPY,
    [INSUFFICIENT_ROLE]: "Only admins can unlink a repository.",
};

export const buildErrorCopy: ErrorCopy = {
    [INSTALLATION_SUSPENDED]: SUSPENDED_COPY,
    [REPO_LINK_NOT_FOUND]: LINK_GONE_COPY,
    [CONCURRENT_MODIFICATION]: "The link changed while the build was being requested. Try again.",
    [IDEMPOTENCY_KEY_REUSE]: "That request clashed with an earlier one. Try again.",
    [GITHUB_RATE_LIMITED]: GITHUB_BUSY_COPY,
    [INSUFFICIENT_ROLE]: "Only developers and above can start builds.",
};

function listCopy(items: readonly string[]): string {
    if (items.length <= 1) return items.join("");
    return `${items.slice(0, -1).join(", ")} and ${items.at(-1) ?? ""}`;
}
