import { asOrgId, asUserId } from "@/shared/domain/ids";
import { describe, expect, it } from "vitest";
import { aMember, aMemberListQuery } from "../domain/testing/fixtures";
import { InMemoryMemberRepository } from "./testing/in-memory";
import { makeMemberUseCases } from "./use-cases";

const orgId = asOrgId("org-kilima");
const wanjiru = aMember({ userId: asUserId("user-wanjiru"), displayName: "Wanjiru Kamau", role: "ADMIN" });

describe("member use cases", () => {
    it("lists one page of members for the query it is given", async () => {
        const members = new InMemoryMemberRepository([aMember(), wanjiru]);
        const query = aMemberListQuery({ q: "wan", role: "ADMIN" });

        const page = await makeMemberUseCases({ members }).listMembers(orgId, query);

        expect(page.items).toEqual([aMember(), wanjiru]);
        expect(members.listQueries).toEqual([{ orgId, query }]);
    });

    it("changes a role and answers with the member as the backend now has them", async () => {
        const members = new InMemoryMemberRepository([wanjiru]);

        const changed = await makeMemberUseCases({ members }).changeRole({ orgId, member: wanjiru, role: "VIEWER" });

        expect(changed.role).toBe("VIEWER");
        expect(members.roleChanges).toEqual([{ orgId, userId: wanjiru.userId, role: "VIEWER" }]);
    });

    it("doesn't send a change to the role the member already has", async () => {
        const members = new InMemoryMemberRepository([wanjiru]);

        const unchanged = await makeMemberUseCases({ members }).changeRole({ orgId, member: wanjiru, role: "ADMIN" });

        expect(unchanged).toBe(wanjiru);
        expect(members.roleChanges).toEqual([]);
    });

    it("removes a member by id, which is also how the caller leaves", async () => {
        const members = new InMemoryMemberRepository([wanjiru]);

        await makeMemberUseCases({ members }).removeMember(orgId, wanjiru.userId);

        expect(members.removals).toEqual([{ orgId, userId: wanjiru.userId }]);
    });

    it("hands ownership over and answers with the new owner", async () => {
        const members = new InMemoryMemberRepository([wanjiru]);

        const owner = await makeMemberUseCases({ members }).transferOwnership(orgId, wanjiru.userId);

        expect(owner).toMatchObject({ userId: wanjiru.userId, role: "OWNER" });
        expect(members.transfers).toEqual([{ orgId, userId: wanjiru.userId }]);
    });
});
