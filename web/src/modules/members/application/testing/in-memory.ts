import type { OrgId, UserId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import { withRole, type AssignableRole, type Member } from "../../domain/member";
import type { MemberListQuery } from "../../domain/member-list-query";
import { aMember } from "../../domain/testing/fixtures";
import type { MemberRepository } from "../ports";

export class InMemoryMemberRepository implements MemberRepository {
    readonly listQueries: { readonly orgId: OrgId; readonly query: MemberListQuery }[] = [];
    readonly roleChanges: { readonly orgId: OrgId; readonly userId: UserId; readonly role: AssignableRole }[] = [];
    readonly removals: { readonly orgId: OrgId; readonly userId: UserId }[] = [];
    readonly transfers: { readonly orgId: OrgId; readonly userId: UserId }[] = [];

    constructor(
        private readonly members: readonly Member[] = [],
        private readonly me: Member = aMember(),
    ) {}

    list(orgId: OrgId, query: MemberListQuery): Promise<Page<Member>> {
        this.listQueries.push({ orgId, query });
        const items = this.members.slice(query.page * query.size, (query.page + 1) * query.size);
        const totalPages = Math.ceil(this.members.length / query.size);
        return Promise.resolve({
            items,
            page: query.page,
            size: query.size,
            totalItems: this.members.length,
            totalPages,
            isFirst: query.page === 0,
            isLast: query.page >= totalPages - 1,
        });
    }

    getMine(): Promise<Member> {
        return Promise.resolve(this.me);
    }

    changeRole(orgId: OrgId, userId: UserId, role: AssignableRole): Promise<Member> {
        this.roleChanges.push({ orgId, userId, role });
        return Promise.resolve(withRole(this.find(userId), role));
    }

    remove(orgId: OrgId, userId: UserId): Promise<void> {
        this.removals.push({ orgId, userId });
        return Promise.resolve();
    }

    transferOwnership(orgId: OrgId, userId: UserId): Promise<Member> {
        this.transfers.push({ orgId, userId });
        return Promise.resolve(withRole(this.find(userId), "OWNER"));
    }

    private find(userId: UserId): Member {
        const member = this.members.find((candidate) => candidate.userId === userId);
        if (member === undefined) throw new Error(`No member ${userId}`);
        return member;
    }
}
