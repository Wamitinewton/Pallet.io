import type { ErrorCopy } from "@/shared/presentation/errors";
import {
    GITHUB_AUTHORIZATION_REQUIRED,
    GITHUB_RATE_LIMITED,
    INSTALLATION_NOT_ACCESSIBLE,
    INSTALLATION_NOT_FOUND,
    INSTALLATION_SUSPENDED,
    INSUFFICIENT_ROLE,
    INVALID_AUTHORIZATION_STATE,
} from "../domain/github-errors";
import type { GitHubAccountType, InstallationStatus } from "../domain/installation";
import type { GitHubReturnFlag } from "../domain/return-intent";

export const GITHUB_TITLE_COPY = "GitHub";
export const CONNECT_GITHUB_COPY = "Connect GitHub";
export const CONTINUE_TO_GITHUB_COPY = "Continue to GitHub";
export const SIGN_IN_WITH_GITHUB_COPY = "Sign in with GitHub";
export const SIGN_OUT_OF_GITHUB_COPY = "Sign out of GitHub";
export const SIGNED_OUT_OF_GITHUB_COPY = "Signed out of GitHub on Pallet";
export const TRY_AGAIN_COPY = "Try again";
export const BACK_COPY = "Back";
export const CONTINUE_COPY = "Continue";

export function githubDescriptionCopy(orgName: string): string {
    return `Install the Pallet GitHub App on the accounts that own your code. Apps in ${orgName} can then build from those repositories.`;
}

export const RETURNED_COPY: Readonly<Record<GitHubReturnFlag, { title: string; body: string }>> = {
    installed: {
        title: "GitHub is connected.",
        body: "The account's repositories now show up when you link a repository to an app.",
    },
    connected: {
        title: "You're signed in to GitHub.",
        body: "Pallet can now check which repositories and installations your GitHub user can reach.",
    },
};
export const LINK_ONE_NOW_COPY = "Link one now";

export const INSTALLATIONS_TITLE_COPY = "Installations";
export const INSTALLATIONS_DESCRIPTION_COPY = "Only admins can add or remove installations.";
export const LOADING_INSTALLATIONS_COPY = "Loading installations";
export const SUSPENDED_FOOT_COPY =
    "A suspended installation was paused on GitHub. Its apps won't build until the account owner unsuspends it there.";
export const NOT_CONNECTED_TITLE_COPY = "GitHub isn't connected yet";
export const NOT_CONNECTED_ADMIN_COPY =
    "Install the Pallet GitHub App on your GitHub organization or personal account. You choose which repositories Pallet can see, and you can change that on GitHub later.";
export const NOT_CONNECTED_COPY =
    "An admin can install the Pallet GitHub App on the GitHub account that owns your code.";
export const MANAGE_ON_GITHUB_COPY = "Manage on GitHub";
export const FORMER_MEMBER_COPY = "a former member";

export const COLUMN_LABEL = {
    account: "GitHub account",
    status: "Status",
    linked: "Added",
    actions: "Actions",
} as const;

export const ACCOUNT_TYPE_LABEL: Readonly<Record<GitHubAccountType, string>> = {
    Organization: "Organization",
    User: "Personal account",
};

export const STATUS_LABEL: Readonly<Record<InstallationStatus, string>> = {
    ACTIVE: "Active",
    SUSPENDED: "Suspended",
};

export function linkedByCopy(name: string): string {
    return `by ${name}`;
}

export function actionsLabel(login: string): string {
    return `Actions for ${login}`;
}

export function removeFromCopy(orgName: string): string {
    return `Remove from ${orgName}`;
}

export function unlinkTitle(login: string): string {
    return `Remove ${login}?`;
}

export function unlinkDescription(orgName: string): string {
    return `Apps in ${orgName} that build from its repositories are disconnected, and pushes stop building until each one is linked again. The Pallet app stays installed on GitHub, and other organizations using it aren't affected.`;
}

export const UNLINK_COPY = "Remove";

export function unlinkedCopy(login: string, orgName: string): string {
    return `${login} removed from ${orgName}`;
}

export function alreadyUnlinkedCopy(login: string, orgName: string): string {
    return `${login} was already removed from ${orgName}`;
}

export const CHOOSE_TITLE_COPY = "Connect GitHub";
export const CHOOSE_DESCRIPTION_COPY =
    "Install the Pallet GitHub App on an account, or use an installation that already exists.";
export const CHOOSE_LABEL_COPY = "How to connect";
export const INSTALL_CHOICE_TITLE_COPY = "Install on a new account";
export const INSTALL_CHOICE_COPY = "Pick a GitHub organization or personal account and choose what Pallet can see.";
export const EXISTING_CHOICE_TITLE_COPY = "Use an existing installation";
export const EXISTING_CHOICE_COPY = "The app is already installed on an account your GitHub user can see.";

export const INSTALL_TITLE_COPY = "Install the Pallet GitHub App";
export const INSTALL_DESCRIPTION_COPY = "We'll send you to GitHub to finish. You'll come back here when you're done.";
export const INSTALL_STEPS_COPY = [
    "Pick the GitHub organization or personal account that owns the code.",
    "Choose all repositories, or only the ones Pallet should see.",
    "GitHub sends you back here and the account shows up in the list.",
] as const;
export const PERMISSIONS_COPY =
    "Pallet asks for read access to code and metadata, and permission to report build status on commits.";

export const EXISTING_TITLE_COPY = "Use an existing installation";
export const EXISTING_DESCRIPTION_COPY = "Choose an account where the Pallet GitHub App is already installed.";
export const EXISTING_SIGN_IN_COPY =
    "Sign in to GitHub so Pallet can list the installations your GitHub user can see. You'll come back here.";
export const INSTALLATION_LABEL_COPY = "Installation";
export const LOADING_VISIBLE_COPY = "Loading the installations you can see";
export const NO_VISIBLE_COPY =
    "Your GitHub user can't see any installation of the Pallet app yet. Install it on an account instead.";
export const INSTALL_INSTEAD_COPY = "Install on an account";
export const LINKED_HERE_COPY = "Linked here";
export const SUSPENDED_COPY = "Suspended on GitHub";
export const LINK_INSTALLATION_COPY = "Link installation";
export const FIRST_PAGE_ONLY_COPY = "Showing the first 100.";

export function linkedCopy(login: string, orgName: string): string {
    return `${login} linked to ${orgName}`;
}

export function alreadyLinkedCopy(login: string, orgName: string): string {
    return `${login} was already linked to ${orgName}`;
}

export const SESSION_TITLE_COPY = "Your GitHub sign-in";
export const SESSION_DESCRIPTION_COPY =
    "Used to check which repositories you can push to when you link one. It's personal to you and lasts up to an hour.";
export const NOT_SIGNED_IN_COPY = "Not signed in to GitHub";
export const NOT_SIGNED_IN_META_COPY = "You'll be asked to sign in when Pallet needs it.";
export const LOADING_SESSION_COPY = "Loading your GitHub sign-in";
export const SESSION_UNAVAILABLE_COPY = "Your GitHub sign-in couldn't be checked.";

const untilTime = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit" });

export function signedInUntilCopy(expiresAt: string): string {
    return `Signed in to GitHub until ${untilTime.format(new Date(expiresAt))}`;
}

export const CALLBACK_WORKING_TITLE_COPY = "Finishing up with GitHub";
export const CALLBACK_WORKING_COPY = "This takes a moment. Keep this tab open.";
export const CALLBACK_AUTHORIZING_TITLE_COPY = "Signing you in to GitHub";
export const CALLBACK_AUTHORIZING_COPY =
    "Linking this installation needs your GitHub sign-in first. You'll come back here.";
export const CALLBACK_REQUESTED_TITLE_COPY = "Waiting for your GitHub admin";
export const CALLBACK_REQUESTED_COPY =
    "Waiting for your GitHub admin to approve the install. Once they do, an admin here can link the account from the GitHub page.";
export const CALLBACK_CANCELLED_TITLE_COPY = "GitHub authorization was cancelled";
export const CALLBACK_CANCELLED_COPY = "Nothing was connected. You can start again whenever you're ready.";
export const CALLBACK_INVALID_TITLE_COPY = "This link from GitHub isn't valid";
export const CALLBACK_INVALID_COPY =
    "It's missing what Pallet needs, or it was changed on the way. Start again from your organization's GitHub page.";
export const CALLBACK_START_AGAIN_TITLE_COPY = "Start again from your organization's GitHub page";
export const CALLBACK_START_AGAIN_COPY =
    "This GitHub sign-in started in another tab, or more than 15 minutes ago, so Pallet can't tell which organization it's for.";
export const CALLBACK_EXPIRED_TITLE_COPY = "That GitHub sign-in expired";
export const CALLBACK_EXPIRED_COPY = "That GitHub sign-in expired or was already used. Try again.";
export const CALLBACK_FAILED_TITLE_COPY = "GitHub couldn't be connected";
export const BACK_TO_GITHUB_PAGE_COPY = "Back to GitHub settings";
export const OPEN_PALLET_COPY = "Open Pallet";

const GITHUB_UNAVAILABLE_COPY = "GitHub is rate limiting Pallet right now. Try again in a few minutes.";

export const linkErrorCopy: ErrorCopy = {
    [INSTALLATION_NOT_ACCESSIBLE]:
        "Your GitHub user can't see that installation. Ask the account's owner on GitHub for access, or choose another.",
    [INSTALLATION_SUSPENDED]: "The app is suspended on that GitHub account. Its owner can unsuspend it on GitHub.",
    [INVALID_AUTHORIZATION_STATE]: CALLBACK_EXPIRED_COPY,
    [GITHUB_AUTHORIZATION_REQUIRED]: "Your GitHub sign-in ended. Sign in again to continue.",
    [GITHUB_RATE_LIMITED]: GITHUB_UNAVAILABLE_COPY,
    [INSUFFICIENT_ROLE]: "Only admins can connect GitHub.",
};

export const startErrorCopy: ErrorCopy = {
    [GITHUB_RATE_LIMITED]: GITHUB_UNAVAILABLE_COPY,
    [INSUFFICIENT_ROLE]: "Only admins can connect GitHub.",
};

export const unlinkErrorCopy: ErrorCopy = {
    [INSTALLATION_NOT_FOUND]: "It was already removed.",
    [INSUFFICIENT_ROLE]: "Only admins can remove an installation.",
};

export const visibleErrorCopy: ErrorCopy = {
    [GITHUB_RATE_LIMITED]: GITHUB_UNAVAILABLE_COPY,
};
