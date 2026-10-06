import type { ApiError } from "@/shared/domain/errors";
import { TOO_MANY_REQUESTS } from "@/shared/domain/request-failure";
import type { ErrorCopy } from "@/shared/presentation/errors";
import { INVITE_LIMITS, type InviteStatus } from "../domain/invite";
import {
    ALREADY_A_MEMBER,
    INSUFFICIENT_ROLE,
    INVITE_ALREADY_PENDING,
    INVITE_NOT_FOUND,
    INVITE_NOT_PENDING,
    MEMBER_PREVIOUSLY_REMOVED,
    PERSONAL_ORG_IMMUTABLE,
} from "../domain/invite-errors";
import type { InviteStatusFilter } from "../domain/invite-list-query";

export const INVITE_PEOPLE_COPY = "Invite people";
export const INVITE_DIALOG_DESCRIPTION_COPY = `They'll get an email with a link. It works for ${String(INVITE_LIMITS.ttlDays)} days.`;
export const SEND_INVITE_COPY = "Send invite";
export const RESEND_PENDING_COPY = "Send it again";
export const NOTHING_PENDING_COPY = "That invite is no longer pending. Send a new one instead.";
export const STATUS_FILTER_LABEL_COPY = "Invite status";
export const INVITED_BY_YOU_COPY = "Invited by you";
export const FORMER_MEMBER_COPY = "A former member";
export const EXPIRED_COPY = "Expired";
export const REVOKE_TITLE_COPY = "Revoke this invite?";
export const KEEP_IT_COPY = "Keep it";
export const REVOKE_INVITE_COPY = "Revoke invite";
export const INVITES_FOOTNOTE_COPY = `Invites last ${String(INVITE_LIMITS.ttlDays)} days. Each one can be emailed up to ${String(INVITE_LIMITS.maxSends)} times, ${String(INVITE_LIMITS.resendCooldownMinutes)} minutes apart.`;
export const PAST_LAST_PAGE_TITLE_COPY = "There's nothing on this page";
export const FIRST_PAGE_COPY = "Go to the first page";

export const STATUS_LABEL: Readonly<Record<InviteStatus, string>> = {
    PENDING: "Pending",
    ACCEPTED: "Accepted",
    REVOKED: "Revoked",
    EXPIRED: "Expired",
};

export const FILTER_LABEL: Readonly<Record<InviteStatusFilter, string>> = { ...STATUS_LABEL, ALL: "All" };

export const COLUMN_LABEL = {
    email: "Email",
    role: "Role",
    status: "Status",
    sent: "Sent",
    expires: "Expires",
} as const;

function plural(count: number, one: string, many: string): string {
    return `${count.toLocaleString("en")} ${count === 1 ? one : many}`;
}

/** Whole minutes once there's at least one left, so a countdown doesn't tick every second. */
export function waitCopy(seconds: number): string {
    return seconds >= 60 ? `${String(Math.ceil(seconds / 60))} min` : `${String(seconds)} s`;
}

export function inviteDialogTitle(orgName: string): string {
    return `Invite someone to ${orgName}`;
}

export function invitedCopy(email: string): string {
    return `Invite sent to ${email}`;
}

export function resentCopy(email: string): string {
    return `Invite sent again to ${email}`;
}

export function invitedByCopy(name: string): string {
    return `Invited by ${name}`;
}

export function sentCountCopy(count: number): string {
    return `${String(count)} of ${String(INVITE_LIMITS.maxSends)} emails`;
}

export function resendInCopy(seconds: number): string {
    return `Resend in ${waitCopy(seconds)}`;
}

export function inviteCountCopy(count: number, status: InviteStatusFilter): string {
    if (status === "ALL") return plural(count, "invite", "invites");
    const label = STATUS_LABEL[status].toLowerCase();
    return plural(count, `${label} invite`, `${label} invites`);
}

export function noInvitesTitle(status: InviteStatusFilter): string {
    return status === "ALL" ? "No one has been invited yet" : `No ${STATUS_LABEL[status].toLowerCase()} invites`;
}

export function noInvitesCopy(status: InviteStatusFilter): string {
    switch (status) {
        case "PENDING":
            return "People you invite show up here until they accept.";
        case "ACCEPTED":
            return "Invites people have accepted show up here.";
        case "REVOKED":
            return "Invites withdrawn before anyone accepted them show up here.";
        case "EXPIRED":
            return `Invites nobody accepted within ${String(INVITE_LIMITS.ttlDays)} days show up here.`;
        case "ALL":
            return "Invite people by email to work in this organization with you.";
    }
}

/** Errors that belong to the address someone typed, shown on the email field. */
export const inviteEmailErrorCopy: Readonly<Record<string, string>> = {
    [ALREADY_A_MEMBER]: "They're already in this organization.",
    [MEMBER_PREVIOUSLY_REMOVED]: "They were removed from this organization; an owner must re-add them.",
    [INVITE_ALREADY_PENDING]: "An invite to this address is already pending.",
};

/** Errors about the organization or the caller rather than the address. */
export const inviteErrorCopy: ErrorCopy = {
    [PERSONAL_ORG_IMMUTABLE]:
        "A personal organization can't have other members. Create a team organization to invite people.",
    [INSUFFICIENT_ROLE]: "Your role no longer lets you invite people with that role.",
};

function cooldownCopy(error: ApiError): string {
    const seconds = error.retryAfterSeconds();
    return seconds === undefined
        ? "This invite was emailed moments ago. Wait a few minutes before sending it again."
        : `This invite was emailed moments ago. You can send it again in ${waitCopy(seconds)}.`;
}

/** The send cap answers `QUOTA_EXCEEDED` with a message that names the limit, so it isn't overridden here. */
export function resendErrorCopy(email: string): ErrorCopy {
    return {
        [TOO_MANY_REQUESTS]: cooldownCopy,
        [INVITE_NOT_PENDING]: `The invite to ${email} is no longer pending.`,
        [INVITE_NOT_FOUND]: `The invite to ${email} is no longer pending.`,
        [INSUFFICIENT_ROLE]: "Only the owner can resend an invite that grants admin.",
    };
}

export function revokeDescription(email: string): string {
    return `The link sent to ${email} stops working. You can invite the same address again later.`;
}

export function revokedCopy(email: string): string {
    return `Invite to ${email} revoked`;
}

/** What to say when revoking lost a race: the invite had already become `status`, if that could be read. */
export function noLongerPendingCopy(email: string, status: InviteStatus | undefined): string {
    switch (status) {
        case "ACCEPTED":
            return `${email} accepted the invite before it could be revoked.`;
        case "REVOKED":
            return `The invite to ${email} was already revoked.`;
        case "EXPIRED":
            return `The invite to ${email} had already expired.`;
        case "PENDING":
        case undefined:
            return `The invite to ${email} is no longer pending.`;
    }
}

export const revokeErrorCopy: ErrorCopy = {
    [INSUFFICIENT_ROLE]: "Only the owner can revoke an invite that grants admin.",
};
