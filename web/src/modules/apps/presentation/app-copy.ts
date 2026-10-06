import type { SortDirection } from "@/shared/domain/page";
import type { ErrorCopy } from "@/shared/presentation/errors";
import { APP_NOT_FOUND, INSUFFICIENT_ROLE } from "../domain/app-errors";
import type { AppSortField } from "../domain/app-list-query";
import { findRegion, type CloudProvider } from "../domain/region-catalog";
import type { AppPageTab } from "./search-params";

export const APPS_TITLE_COPY = "Apps";
export const APPS_DESCRIPTION_COPY = "Each app runs in one cloud region and builds from one repository.";
export const APPS_LIST_LABEL_COPY = "Apps";
export const LOADING_APPS_COPY = "Loading apps";
export const NEW_APP_COPY = "New app";
export const NEW_APP_DESCRIPTION_COPY = "Name it and choose where it runs. You'll link its repository next.";
export const CREATE_APP_COPY = "Create app";
export const FIXED_AT_CREATION_CALLOUT_COPY =
    "The cloud and region can't be changed once the app exists. To move an app, create a new one where it should run.";

export const NAME_LABEL_COPY = "Name";
export const SLUG_LABEL_COPY = "Slug";
export const SLUG_TAKEN_COPY = "Another app already uses that slug. Try another.";
export const CLOUD_LABEL_COPY = "Cloud";
export const REGION_LABEL_COPY = "Region";
export const REGION_PLACEHOLDER_COPY = "Choose a region";
export const REGION_HINT_COPY = "Pick the one closest to your users.";
export const INVALID_REGION_COPY = "This region isn't available any more. Choose another.";
export const TEAM_LABEL_COPY = "Team";
export const OPTIONAL_COPY = "(optional)";
export const NO_TEAM_COPY = "No team";
export const TEAM_GONE_COPY = "That team no longer exists. Choose another.";
export const UNKNOWN_TEAM_COPY = "Unknown team";
export const LOADING_TEAMS_COPY = "Loading teams…";

export const PROVIDER_NAME: Readonly<Record<CloudProvider, string>> = {
    AWS: "Amazon Web Services",
    GCP: "Google Cloud",
};

export const PROVIDER_SHORT_NAME: Readonly<Record<CloudProvider, string>> = {
    AWS: "AWS",
    GCP: "GCP",
};

export const SEARCH_LABEL_COPY = "Search apps";
export const SEARCH_PLACEHOLDER_COPY = "Search by name or slug";
export const ALL_TEAMS_COPY = "All teams";
export const ALL_CLOUDS_COPY = "All clouds";
export const CLEAR_FILTERS_COPY = "Clear filters";
export const NO_APPS_TITLE_COPY = "No apps yet";
export const NO_APPS_CREATOR_COPY = "Create an app to choose where it runs, then link the repository it builds from.";
export const NO_APPS_COPY = "A developer or above can create the first one.";
export const NO_MATCHES_TITLE_COPY = "No apps match these filters";
export const NO_MATCHES_COPY = "Try another search, team or cloud, or clear the filters.";
export const PAST_LAST_PAGE_TITLE_COPY = "There's nothing on this page";
export const FIRST_PAGE_COPY = "Go to the first page";
export const WHO_CAN_CREATE_COPY = "Viewers can see apps. Developers and above can create them.";
export const NO_REPOSITORY_COPY = "No repository";

export const COLUMN_LABEL = {
    app: "App",
    repository: "Repository",
    runsOn: "Runs on",
    team: "Team",
    updated: "Updated",
} as const;

export const SORT_LABEL: Readonly<Record<`${AppSortField},${SortDirection}`, string>> = {
    "createdAt,desc": "Newest first",
    "createdAt,asc": "Oldest first",
    "name,asc": "Name, A to Z",
    "name,desc": "Name, Z to A",
};

export const TABS_LABEL_COPY = "App sections";
export const TAB_LABEL: Readonly<Record<AppPageTab, string>> = {
    repository: "Repository",
    settings: "Settings",
};

export const GENERAL_TITLE_COPY = "General";
export const GENERAL_DESCRIPTION_COPY = "Developers and above can change these.";
export const APP_NAME_LABEL_COPY = "App name";
export const APPLIES_RIGHT_AWAY_COPY = "Changes apply right away.";
export const APP_UPDATED_COPY = "App updated";
export const NOT_UPDATED_COPY = "The changes weren't saved.";
export const CONCURRENT_MODIFICATION_COPY =
    "Someone else changed this app while you were editing. The form now shows the latest settings: make your changes again.";

export const FIXED_TITLE_COPY = "Fixed at creation";
export const FIXED_DESCRIPTION_COPY = "To move an app to another cloud or region, create a new app there.";
export const APP_ID_LABEL_COPY = "App ID";
export const CREATED_LABEL_COPY = "Created";

export const DELETE_TITLE_COPY = "Delete app";
export const DELETE_DESCRIPTION_COPY = "Admins only. This removes the app and its repository link.";
export const DELETE_APP_COPY = "Delete app";
export const DELETION_MISMATCH_COPY = "That doesn't match the app's slug. Type it exactly as shown.";

export const APP_UNAVAILABLE_TITLE_COPY = "This app isn't available";
export const APP_UNAVAILABLE_COPY = "It may have been deleted, or the link is wrong.";
export const BACK_TO_APPS_COPY = "Back to apps";
export const LOADING_APP_COPY = "Loading app";

export const TEAM_APPS_TITLE_COPY = "Apps";
export const NO_TEAM_APPS_COPY = "No apps belong to this team yet. Choose it as an app's team to list it here.";
export const LOADING_TEAM_APPS_COPY = "Loading the team's apps";

const createdDate = new Intl.DateTimeFormat("en-GB", { dateStyle: "long", timeZone: "UTC" });

export function createdOnCopy(createdAt: string): string {
    return createdDate.format(new Date(createdAt));
}

/** `eu-west-1` reads as "Ireland (eu-west-1)"; a region the catalog dropped keeps its bare id. */
export function regionLabel(provider: CloudProvider, regionId: string): string {
    const region = findRegion(provider, regionId);
    return region === undefined ? regionId : `${region.location} (${region.id})`;
}

export function regionCountCopy(count: number): string {
    return `${String(count)} ${count === 1 ? "region" : "regions"}`;
}

export function slugHintCopy(orgName: string): string {
    return `Used in URLs. Unique within ${orgName}, and it can't change later.`;
}

export function appCountCopy(count: number): string {
    return `${count.toLocaleString("en")} ${count === 1 ? "app" : "apps"}`;
}

export function openAppLabel(name: string): string {
    return `Open ${name}`;
}

export function appCreatedCopy(name: string): string {
    return `${name} created`;
}

export function deleteAppLabel(name: string): string {
    return `Delete ${name}`;
}

export function deleteConfirmTitle(name: string): string {
    return `Delete ${name}?`;
}

export function deleteConfirmDescription(orgName: string): string {
    return `The app and its repository link are removed for everyone in ${orgName}. You can't undo this.`;
}

export function appDeletedCopy(name: string): string {
    return `${name} deleted`;
}

export function seeAllTeamAppsCopy(count: number): string {
    return `See all ${appCountCopy(count)}`;
}

export const createErrorCopy: ErrorCopy = {
    [INSUFFICIENT_ROLE]: "Only developers and above can create apps.",
};

export const updateErrorCopy: ErrorCopy = {
    [APP_NOT_FOUND]: "This app has been deleted.",
    [INSUFFICIENT_ROLE]: "Only developers and above can change an app.",
};

export const deleteErrorCopy: ErrorCopy = {
    [APP_NOT_FOUND]: "This app was already deleted.",
    [INSUFFICIENT_ROLE]: "Only admins can delete an app.",
};
