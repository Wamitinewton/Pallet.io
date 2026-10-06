import type { OrgId, UserId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import type { AssignableRole, Member } from "../domain/member";
import type { MemberListQuery } from "../domain/member-list-query";

export interface MemberRepository {
    list(orgId: OrgId, query: MemberListQuery): Promise<Page<Member>>;
    getMine(orgId: OrgId): Promise<Member>;
    changeRole(orgId: OrgId, userId: UserId, role: AssignableRole): Promise<Member>;
    /** Removes someone else, or the caller themselves when `userId` is their own: leaving. */
    remove(orgId: OrgId, userId: UserId): Promise<void>;
    /** @returns the new owner. */
    transferOwnership(orgId: OrgId, userId: UserId): Promise<Member>;
}
