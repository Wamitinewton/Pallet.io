import { REAUTHENTICATION_REQUIRED } from "@/modules/session";
import type { SortDirection } from "@/shared/domain/page";
import type { Role } from "@/shared/domain/role";
import type { ErrorCopy } from "@/shared/presentation/errors";
import { ROLE_LABEL } from "@/shared/presentation/roles";
import {
    CONCURRENT_MODIFICATION,
    INSUFFICIENT_ROLE,
    INVALID_ROLE_TRANSITION,
    LAST_OWNER,
    MEMBER_NOT_FOUND,
} from "../domain/member-errors";
import type { MemberSortField } from "../domain/member-list-query";

export const MEMBERS_TITLE_COPY = "Members";
export const TABS_LABEL_COPY = "Members and invites";
export const TAB_LABEL = { members: "Members", invites: "Invites" } as const;
export const SEARCH_LABEL_COPY = "Search members";
export const SEARCH_PLACEHOLDER_COPY = "Search by name or email";
export const ALL_ROLES_COPY = "All roles";
export const NO_MATCHES_TITLE_COPY = "No members match";
export const NO_REMOVED_TITLE_COPY = "No one has been removed";
export const NO_REMOVED_COPY = "People removed from this organization show up here.";
export const CLEAR_FILTERS_COPY = "Clear filters";
export const PAST_LAST_PAGE_TITLE_COPY = "There's nothing on this page";
export const FIRST_PAGE_COPY = "Go to the first page";
export const OWNER_ONLY_ROLE_NOTE_COPY = "To make someone the owner, use Make owner instead.";
export const TRANSFER_STEP_UP_COPY = "You may be asked for your password if you signed in a while ago.";
export const PICKER_NO_MATCHES_COPY = "No members match.";
export const PICKER_SEARCHING_COPY = "Searching…";

export const STATUS_LABEL = { ACTIVE: "Active", REMOVED: "Removed" } as const;

export const SORT_LABEL: Readonly<Record<`${MemberSortField},${SortDirection}`, string>> = {
    "displayName,asc": "Name, A to Z",
    "displayName,desc": "Name, Z to A",
    "joinedAt,desc": "Newest first",
    "joinedAt,asc": "Oldest first",
    "role,desc": "Role, owner first",
    "role,asc": "Role, viewer first",
};

export const SORT_COLUMN_LABEL: Readonly<Record<MemberSortField, string>> = {
    displayName: "Name",
    role: "Role",
    joinedAt: "Joined",
};

function firstName(name: string): string {
    return name.trim().split(/\s+/)[0] ?? name;
}

function withArticle(role: Role): string {
    const label = ROLE_LABEL[role].toLowerCase();
    return /^[aeiou]/.test(label) ? `an ${label}` : `a ${label}`;
}

function plural(count: number, one: string, many: string): string {
    return `${count.toLocaleString("en")} ${count === 1 ? one : many}`;
}

export function membersDescription(orgName: string): string {
    return `Everyone in ${orgName} and what they can do. Admins invite and remove people; the owner changes roles.`;
}

export function memberCountCopy(count: number, removed: boolean): string {
    return removed ? plural(count, "removed member", "removed members") : plural(count, "member", "members");
}

export function oneOwnerCopy(orgName: string): string {
    return `${orgName} has one owner. Hand over ownership from a member's menu.`;
}

export function noMatchesCopy(q: string): string {
    return q === "" ? "No one matches these filters." : `Nobody's name or email starts with “${q}”.`;
}

export function actionsLabel(name: string): string {
    return `Actions for ${name}`;
}

export function removeFromCopy(orgName: string): string {
    return `Remove from ${orgName}`;
}

export function leaveCopy(orgName: string): string {
    return `Leave ${orgName}`;
}

export function changeRoleTitle(name: string): string {
    return `Change ${name}'s role`;
}

export function changeRoleDescription(name: string, orgName: string): string {
    return `Pick what ${firstName(name)} should be able to do in ${orgName}.`;
}

export function roleChangedCopy(name: string, role: Role): string {
    return `${name} is now ${withArticle(role)}`;
}

export function roleNotChangedCopy(name: string): ErrorCopy {
    return {
        [LAST_OWNER]: `${name} is the only owner, so their role can't change. Make someone else the owner first.`,
        [INVALID_ROLE_TRANSITION]: `${name}'s role can't be changed to that. They may have become the owner meanwhile.`,
        [CONCURRENT_MODIFICATION]: `Someone else changed ${name}'s membership at the same time. The list now shows the latest.`,
        [MEMBER_NOT_FOUND]: `${name} is no longer a member.`,
        [INSUFFICIENT_ROLE]: "Only the owner can change roles.",
    };
}

export function removeTitle(name: string): string {
    return `Remove ${name}?`;
}

export function removeDescription(orgName: string): string {
    return `They lose access to ${orgName} straight away and are taken off every team. Their Pallet account and other organizations aren't affected.`;
}

export function removedCopy(name: string, orgName: string): string {
    return `${name} removed from ${orgName}`;
}

export function alreadyRemovedCopy(name: string): string {
    return `${name} was already removed.`;
}

export const removeErrorCopy: ErrorCopy = {
    [LAST_OWNER]: "The owner can't be removed. Ownership has to move to someone else first.",
    [CONCURRENT_MODIFICATION]: "Someone else changed this membership at the same time. Try again.",
    [INSUFFICIENT_ROLE]: "Admins can remove developers and viewers only.",
};

export function leaveTitle(orgName: string): string {
    return `Leave ${orgName}?`;
}

export function leaveDescription(orgName: string): string {
    return `You lose access to ${orgName} straight away and are taken off every team. To come back, someone has to invite you again.`;
}

export function leftCopy(orgName: string): string {
    return `You left ${orgName}`;
}

export const leaveErrorCopy: ErrorCopy = {
    [LAST_OWNER]: "The owner can't leave. Make someone else the owner first.",
    [CONCURRENT_MODIFICATION]: "Your membership changed at the same time. Try again.",
};

export function transferTitle(name: string): string {
    return `Make ${name} the owner?`;
}

export function transferDescription(name: string): string {
    return `An organization has one owner. You'll become an admin, and only ${firstName(name)} can give ownership back.`;
}

export function transferredCopy(name: string, orgName: string): string {
    return `${name} now owns ${orgName}`;
}

export function transferErrorCopy(name: string, orgName: string): ErrorCopy {
    return {
        [REAUTHENTICATION_REQUIRED]: "Confirm your password to transfer ownership.",
        [INVALID_ROLE_TRANSITION]: `${name} can't become the owner. They may have left, or already own ${orgName}.`,
        [CONCURRENT_MODIFICATION]: "Someone else changed a membership at the same time. Try again.",
        [MEMBER_NOT_FOUND]: `${name} is no longer a member.`,
        [INSUFFICIENT_ROLE]: "Only the owner can hand over ownership.",
    };
}
