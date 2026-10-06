import { error } from "@/test/msw/envelopes";
import { inviteDto } from "@/test/msw/org-team";
import { screen, waitFor, within } from "@testing-library/react";
import type { OnUrlUpdateFunction } from "nuqs/adapters/testing";
import { describe, expect, it } from "vitest";
import { AMANI, DAVID, FATUMA, GRACE, InvitesBackend, LUCY, OTIENO, renderInvites } from "./testing/render-invites";

const rows = () =>
    within(screen.getByRole("table", { name: "Invites" }))
        .getAllByRole("row")
        .slice(1);
const emails = () => rows().map((row) => within(row).getByText(/^\S+@\S+$/).textContent);
const rowOf = (email: string) => screen.getByRole("row", { name: new RegExp(email) });

function urlUpdates() {
    const updates: string[] = [];
    const onUrlUpdate: OnUrlUpdateFunction = ({ queryString }) => {
        updates.push(queryString);
    };
    return { updates, onUrlUpdate };
}

describe("InvitesTab", () => {
    it("lists pending invites with their role, sends, expiry and who sent them", async () => {
        const { backend } = await renderInvites();

        expect(emails()).toEqual([DAVID.email, LUCY.email]);
        expect(backend.listed("status")).toEqual(["PENDING"]);

        const david = rowOf(DAVID.email);
        expect(within(david).getByText("Developer")).toBeInTheDocument();
        expect(within(david).getByText("Pending")).toBeInTheDocument();
        expect(within(david).getByText("2 of 4 emails")).toBeInTheDocument();
        expect(within(david).getByText("tomorrow")).toBeInTheDocument();
        expect(await within(david).findByText("Invited by Grace Njeri")).toBeInTheDocument();
        expect(within(rowOf(LUCY.email)).getByText("Invited by you")).toBeInTheDocument();
        expect(within(rowOf(LUCY.email)).getByText("in 3 days")).toBeInTheDocument();

        expect(screen.getByText("2 pending invites")).toBeInTheDocument();
        expect(
            screen.getByText("Invites last 3 days. Each one can be emailed up to 4 times, 5 minutes apart."),
        ).toBeInTheDocument();
    });

    it("names a former member when whoever sent the invite has left", async () => {
        await renderInvites({ searchParams: "?inviteStatus=EXPIRED" });

        expect(await within(rowOf(OTIENO.email)).findByText("Invited by A former member")).toBeInTheDocument();
    });

    it("shows a pending invite past its expiry as expired, with nothing to resend or revoke", async () => {
        const lapsed = inviteDto({ expiresAt: "2026-01-15T11:59:00Z" });
        await renderInvites({ backend: new InvitesBackend([lapsed]) });

        const row = rowOf(lapsed.email);
        expect(within(row).getAllByText("Expired")).toHaveLength(2);
        expect(within(row).queryByRole("button", { name: /Resend/ })).not.toBeInTheDocument();
        expect(within(row).queryByRole("button", { name: /Revoke/ })).not.toBeInTheDocument();
        expect(within(row).getByRole("button", { name: `Invite again: ${lapsed.email}` })).toBeInTheDocument();
    });

    it("keeps the status filter in the URL and asks for every status under All", async () => {
        const { updates, onUrlUpdate } = urlUpdates();
        const { user, backend } = await renderInvites({ onUrlUpdate });
        const filter = screen.getByRole("radiogroup", { name: "Invite status" });

        await user.click(within(filter).getByRole("radio", { name: "All" }));

        await waitFor(() => {
            expect(emails()).toEqual([DAVID.email, LUCY.email, OTIENO.email]);
        });
        expect(updates.at(-1)).toBe("?inviteStatus=ALL");
        expect(backend.listed("status")).toEqual(["PENDING", null]);
        expect(within(rowOf(OTIENO.email)).getByText("Expired")).toBeInTheDocument();
        expect(screen.getByText("3 invites")).toBeInTheDocument();
    });

    it("reads the status and page from the URL", async () => {
        const { backend } = await renderInvites({ searchParams: "?inviteStatus=REVOKED&invitePage=2" });

        expect(Object.fromEntries(backend.listRequests[0] ?? [])).toEqual({
            status: "REVOKED",
            page: "1",
            size: "20",
            sort: "createdAt,desc",
        });
    });

    it("offers to invite someone when nothing is pending", async () => {
        const { user } = await renderInvites({ backend: new InvitesBackend([OTIENO]) });

        expect(screen.getByRole("heading", { name: "No pending invites" })).toBeInTheDocument();
        const [, emptyStateButton] = screen.getAllByRole("button", { name: "Invite people" });
        await user.click(emptyStateButton ?? screen.getByRole("button", { name: "Invite people" }));

        expect(await screen.findByRole("dialog", { name: "Invite someone to Kilima Labs" })).toBeInTheDocument();
    });

    it("draws the error with a way to try again", async () => {
        const backend = new InvitesBackend();
        backend.listFailure = () => error(503, "SERVICE_UNAVAILABLE");
        const { user } = await renderInvites({ backend });

        expect(await screen.findByText(/temporarily unavailable/)).toBeInTheDocument();
        backend.listFailure = undefined;
        await user.click(screen.getByRole("button", { name: /Try again/ }));

        expect(await screen.findByRole("table", { name: "Invites" })).toBeInTheDocument();
    });

    it("gives an admin no actions on an invite that grants admin", async () => {
        const forAdmin = inviteDto({
            id: "1d2c3b4a-5f6e-4d7c-b8a9-0f1e2d3c4b5a",
            email: "wanja@kilimalabs.co",
            role: "ADMIN",
            invitedByUserId: AMANI.userId,
        });
        await renderInvites({ backend: new InvitesBackend([forAdmin, DAVID], GRACE) });

        expect(within(rowOf(forAdmin.email)).queryByRole("button")).not.toBeInTheDocument();
        expect(within(rowOf(DAVID.email)).getByRole("button", { name: /^Resend/ })).toBeInTheDocument();
        expect(within(rowOf(DAVID.email)).getByText("Invited by you")).toBeInTheDocument();
    });

    it.each([
        ["a developer", FATUMA, "TEAM"],
        ["the owner of a personal organization", AMANI, "PERSONAL"],
    ] as const)("is hidden from %s, who never asks for invites", async (_, me, orgKind) => {
        const backend = new InvitesBackend(undefined, me);
        await renderInvites({ backend, orgKind });

        expect(screen.queryByRole("table")).not.toBeInTheDocument();
        expect(screen.queryByRole("radiogroup", { name: "Invite status" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Invite people" })).not.toBeInTheDocument();
        expect(backend.listRequests).toEqual([]);
    });
});
