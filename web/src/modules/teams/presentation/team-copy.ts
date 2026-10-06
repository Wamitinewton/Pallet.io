import type { SortDirection } from "@/shared/domain/page";
import type { ErrorCopy } from "@/shared/presentation/errors";
import { INSUFFICIENT_ROLE, MEMBER_NOT_FOUND, TEAM_NOT_FOUND } from "../domain/team-errors";
import type { TeamSortField } from "../domain/team-list-query";

export const TEAMS_TITLE_COPY = "Teams";
export const TEAMS_DESCRIPTION_COPY =
    "Group people by what they work on, and give each app a team that owns it. Someone can be on more than one team.";
export const NEW_TEAM_COPY = "New team";
export const NEW_TEAM_DESCRIPTION_COPY = "You can add people once it's created.";
export const CREATE_TEAM_COPY = "Create team";
export const SLUG_LABEL_COPY = "Slug";
export const SLUG_HINT_COPY = "Lowercase letters, numbers and hyphens. It can't change later.";
export const SLUG_TAKEN_COPY = "Another team already uses that slug. Try another.";
export const NO_TEAMS_TITLE_COPY = "No teams yet";
export const NO_TEAMS_ADMIN_COPY = "Teams group the people who work on the same apps. Create one to get started.";
export const NO_TEAMS_COPY = "Teams group the people who work on the same apps. An admin can create the first one.";
export const PAST_LAST_PAGE_TITLE_COPY = "There's nothing on this page";
export const FIRST_PAGE_COPY = "Go to the first page";
export const TEAMS_LIST_LABEL_COPY = "Teams";
export const LOADING_TEAMS_COPY = "Loading teams";

export const SORT_LABEL: Readonly<Record<`${TeamSortField},${SortDirection}`, string>> = {
    "name,asc": "Name, A to Z",
    "name,desc": "Name, Z to A",
    "createdAt,desc": "Newest first",
    "createdAt,asc": "Oldest first",
};

export const PEOPLE_TITLE_COPY = "People";
export const ADD_PEOPLE_COPY = "Add people";
export const ROLES_COME_FROM_ORG_COPY = "Roles come from the organization. A team doesn't change what someone can do.";
export const NO_PEOPLE_TITLE_COPY = "No one's on this team yet";
export const NO_PEOPLE_ADMIN_COPY = "Add members of the organization to show who works on this team's apps.";
export const NO_PEOPLE_COPY = "An admin can add members of the organization to it.";
export const LOADING_PEOPLE_COPY = "Loading people";
export const YOU_COPY = "(you)";
export const ADD_TO_TEAM_COPY = "Add to team";
export const MEMBER_PICKER_LABEL_COPY = "Member";
export const CHANGE_PICK_COPY = "Change";
export const REMOVE_COPY = "Remove";
export const REMOVE_FROM_TEAM_COPY = "Remove from team";

export const RENAME_TITLE_COPY = "Team name";
export const NAME_LABEL_COPY = "Name";
export const RENAMED_COPY = "Team renamed";
export const NOT_RENAMED_COPY = "The name wasn't saved.";
export const DELETE_TITLE_COPY = "Delete team";
export const DELETE_TEAM_COPY = "Delete team";
export const DELETION_MISMATCH_COPY = "That doesn't match the team's name. Type it exactly as shown.";

export const TEAM_UNAVAILABLE_TITLE_COPY = "This team isn't available";
export const TEAM_UNAVAILABLE_COPY = "It may have been deleted, or the link is wrong.";
export const BACK_TO_TEAMS_COPY = "Back to teams";

const createdDate = new Intl.DateTimeFormat("en-GB", { dateStyle: "long", timeZone: "UTC" });

function firstName(name: string): string {
    return name.trim().split(/\s+/)[0] ?? name;
}

export function peopleCountCopy(count: number): string {
    return `${count.toLocaleString("en")} ${count === 1 ? "person" : "people"}`;
}

export function createdCopy(createdAt: string): string {
    return `created ${createdDate.format(new Date(createdAt))}`;
}

export function teamCreatedCopy(name: string): string {
    return `${name} team created`;
}

export function addPeopleTitle(teamName: string): string {
    return `Add people to ${teamName}`;
}

export function addPeopleDescription(orgName: string): string {
    return `Only members of ${orgName} can join a team.`;
}

export const INVITE_SOMEONE_COPY = "Invite someone new";
export const INVITE_FIRST_COPY = "first if they're not in yet.";

export function addedCopy(name: string, teamName: string): string {
    return `${name} added to ${teamName}`;
}

export function alreadyInTeamCopy(name: string, teamName: string): string {
    return `${name} is already on ${teamName}`;
}

export function addErrorCopy(name: string, orgName: string): ErrorCopy {
    return {
        [MEMBER_NOT_FOUND]: `${name} is no longer a member of ${orgName}, so they can't join a team.`,
        [TEAM_NOT_FOUND]: "This team has been deleted.",
        [INSUFFICIENT_ROLE]: "Only admins can add people to a team.",
    };
}

export function removeLabel(name: string, teamName: string): string {
    return `Remove ${name} from ${teamName}`;
}

export function removeTitle(name: string, teamName: string): string {
    return `Remove ${name} from ${teamName}?`;
}

export function removeDescription(name: string, orgName: string): string {
    return `${firstName(name)} stays in ${orgName} with the same role, and can be added back at any time.`;
}

export function removedCopy(name: string, teamName: string): string {
    return `${name} removed from ${teamName}`;
}

export function alreadyRemovedCopy(name: string, teamName: string): string {
    return `${name} was already off ${teamName}`;
}

export function notRemovedCopy(name: string): ErrorCopy {
    return {
        [TEAM_NOT_FOUND]: "This team has been deleted.",
        [INSUFFICIENT_ROLE]: `Only admins can take ${name} off a team.`,
    };
}

export const SLUG_STAYS_COPY = "The slug stays";

export function deleteImpactCopy(memberCount: number, orgName: string): string {
    const people =
        memberCount === 0
            ? "Nobody is on it, so nobody is affected."
            : `The ${peopleCountCopy(memberCount)} on it stay in ${orgName}.`;
    return `${people} Any apps it owns are left without a team.`;
}

export function deleteTeamLabel(teamName: string): string {
    return `Delete ${teamName}`;
}

export function deleteConfirmTitle(teamName: string): string {
    return `Delete the ${teamName} team?`;
}

export function deleteConfirmDescription(memberCount: number, orgName: string): string {
    const people =
        memberCount === 0 ? "" : ` The team's ${peopleCountCopy(memberCount)} stay in ${orgName} with the same roles.`;
    return `Nobody loses access.${people} Its apps are left without a team. You can't undo this.`;
}

export function teamDeletedCopy(teamName: string): string {
    return `${teamName} team deleted`;
}

export const deleteErrorCopy: ErrorCopy = {
    [TEAM_NOT_FOUND]: "This team was already deleted.",
    [INSUFFICIENT_ROLE]: "Only admins can delete a team.",
};

export const createErrorCopy: ErrorCopy = {
    [INSUFFICIENT_ROLE]: "Only admins can create teams.",
};

export const renameErrorCopy: ErrorCopy = {
    [TEAM_NOT_FOUND]: "This team has been deleted.",
    [INSUFFICIENT_ROLE]: "Only admins can rename a team.",
};
