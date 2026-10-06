import { fixedClock } from "@/shared/domain/clock";
import { ApiError } from "@/shared/domain/errors";
import { asInviteId, asOrgId } from "@/shared/domain/ids";
import { describe, expect, it } from "vitest";
import { ALL_PENDING_QUERY, RECENT_INVITES_QUERY } from "../domain/invite-list-query";
import { anInvite } from "../domain/testing/fixtures";
import { InMemoryInviteAcceptanceRepository, InMemoryInviteRepository } from "./testing/in-memory";
import { makeInviteUseCases } from "./use-cases";

const useCasesFor = (invites: InMemoryInviteRepository) =>
    makeInviteUseCases({
        invites,
        acceptance: new InMemoryInviteAcceptanceRepository(),
        clock: fixedClock("2026-01-15T12:00:00Z"),
    });

const orgId = asOrgId("org-kilima");
const david = anInvite();
const lucy = anInvite({ id: asInviteId("invite-lucy"), email: "lucy@wambui.dev", role: "VIEWER" });

describe("invite use cases", () => {
    it("lists one page of invites for the query it is given", async () => {
        const invites = new InMemoryInviteRepository([david, lucy]);
        const query = { status: "PENDING", page: 0, size: 20 } as const;

        const page = await useCasesFor(invites).listInvites(orgId, query);

        expect(page.items).toEqual([david, lucy]);
        expect(invites.listQueries).toEqual([{ orgId, query }]);
    });

    it("creates an invite for the address and role given", async () => {
        const invites = new InMemoryInviteRepository();

        const created = await useCasesFor(invites).createInvite(orgId, {
            email: "mercy@kilimalabs.co",
            role: "VIEWER",
        });

        expect(created).toMatchObject({ email: "mercy@kilimalabs.co", role: "VIEWER", status: "PENDING" });
        expect(invites.created).toEqual([{ orgId, invite: { email: "mercy@kilimalabs.co", role: "VIEWER" } }]);
    });

    it("resends an invite and answers with it as the backend now has it", async () => {
        const invites = new InMemoryInviteRepository([david]);

        const resent = await useCasesFor(invites).resendInvite(orgId, david.id);

        expect(resent.sendCount).toBe(2);
        expect(invites.resent).toEqual([{ orgId, inviteId: david.id }]);
    });
});

describe("resendPendingInvite", () => {
    it("finds the pending invite for the address, whatever its case, and resends it", async () => {
        const invites = new InMemoryInviteRepository([david, lucy]);

        const resent = await useCasesFor(invites).resendPendingInvite(orgId, "Lucy@Wambui.dev");

        expect(resent).toMatchObject({ id: lucy.id, sendCount: 2 });
        expect(invites.listQueries).toEqual([{ orgId, query: ALL_PENDING_QUERY }]);
        expect(invites.resent).toEqual([{ orgId, inviteId: lucy.id }]);
    });

    it("answers nothing when no invite to that address is pending any more", async () => {
        const invites = new InMemoryInviteRepository([{ ...lucy, status: "ACCEPTED" }]);

        expect(await useCasesFor(invites).resendPendingInvite(orgId, lucy.email)).toBeUndefined();
        expect(invites.resent).toEqual([]);
    });
});

describe("revokeInvite", () => {
    it("revokes a pending invite", async () => {
        const invites = new InMemoryInviteRepository([david]);

        expect(await useCasesFor(invites).revokeInvite(orgId, david)).toEqual({ kind: "revoked" });
        expect(invites.revoked).toEqual([{ orgId, inviteId: david.id }]);
    });

    it("says what an invite became when it stopped being pending first", async () => {
        const invites = new InMemoryInviteRepository([{ ...david, status: "ACCEPTED" }]);

        const outcome = await useCasesFor(invites).revokeInvite(orgId, david);

        expect(outcome).toEqual({ kind: "no-longer-pending", current: { ...david, status: "ACCEPTED" } });
        expect(invites.listQueries.map(({ query }) => query)).toEqual([RECENT_INVITES_QUERY]);
    });

    it("still reports the race when what it became can't be read", async () => {
        const invites = new InMemoryInviteRepository([{ ...david, status: "REVOKED" }]);
        invites.listFailure = new Error("unreachable");

        expect(await useCasesFor(invites).revokeInvite(orgId, david)).toEqual({
            kind: "no-longer-pending",
            current: undefined,
        });
    });

    it("passes any other refusal on", async () => {
        const invites = new InMemoryInviteRepository([david]);
        invites.revoke = () => Promise.reject(new ApiError(403, "INSUFFICIENT_ROLE", "No", [], {}, undefined));

        await expect(useCasesFor(invites).revokeInvite(orgId, david)).rejects.toMatchObject({
            code: "INSUFFICIENT_ROLE",
        });
    });
});
