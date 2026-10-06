import type { UserId } from "@/shared/domain/ids";
import type { IsoInstant } from "@/shared/domain/instant";
import type { Role } from "@/shared/domain/role";

export const MEMBER_STATUSES = ["ACTIVE", "REMOVED"] as const;

export type MemberStatus = (typeof MEMBER_STATUSES)[number];

/** Ownership is never assigned by a role change, only by a transfer. */
export const ASSIGNABLE_ROLES = ["ADMIN", "DEVELOPER", "VIEWER"] as const satisfies readonly Role[];

export type AssignableRole = (typeof ASSIGNABLE_ROLES)[number];

export interface Member {
    readonly userId: UserId;
    readonly email: string;
    readonly displayName: string;
    readonly role: Role;
    readonly status: MemberStatus;
    readonly joinedAt: IsoInstant;
}

export function withRole(member: Member, role: Role): Member {
    return { ...member, role };
}
