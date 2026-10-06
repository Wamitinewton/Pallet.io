import { asOrgId } from "@/shared/domain/ids";
import { describe, expect, it } from "vitest";
import { aMember } from "../domain/testing/fixtures";
import { makeGetMyMembership } from "./get-my-membership";
import { InMemoryMemberRepository } from "./testing/in-memory";

describe("getMyMembership", () => {
    it("reads the caller's membership in that organization", async () => {
        const me = aMember({ role: "ADMIN" });

        expect(await makeGetMyMembership(new InMemoryMemberRepository([], me))(asOrgId("org-1"))).toEqual(me);
    });
});
