import { error } from "@/test/msw/envelopes";
import { orgDto } from "@/test/msw/org-team";
import { server } from "@/test/msw/server";
import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { FATUMA, KILIMA, MembersBackend, memberUrl, renderMembers, TEAM, WANJIRU } from "./testing/render-members";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router, usePathname: () => "/orgs/org-kilima/members" }));

function replyToRemovals(reply: () => Response = () => new HttpResponse(null, { status: 204 })) {
    const removed: string[] = [];
    server.use(
        http.delete(memberUrl(":userId"), ({ params }) => {
            removed.push(String(params.userId));
            return reply();
        }),
    );
    return removed;
}

async function openMenuFor(user: Awaited<ReturnType<typeof renderMembers>>["user"], name: string) {
    await user.click(screen.getByRole("button", { name: `Actions for ${name}` }));
    return screen.findByRole("menu");
}

beforeEach(() => {
    vi.clearAllMocks();
});

describe("RemoveMemberDialog", () => {
    it("never offers an admin a way to remove another admin or the owner", async () => {
        const { user } = await renderMembers({ backend: new MembersBackend([...TEAM], WANJIRU) });

        expect(screen.queryByRole("button", { name: "Actions for Grace Njeri" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Actions for Amani Otieno" })).not.toBeInTheDocument();

        const menu = await openMenuFor(user, "Fatuma Hassan");
        expect(
            within(menu)
                .getAllByRole("menuitem")
                .map((item) => item.textContent),
        ).toEqual(["Remove from Kilima Labs"]);
    });

    it("removes a member and refreshes everything their removal changes", async () => {
        const removed = replyToRemovals();
        const backend = new MembersBackend([...TEAM], WANJIRU);
        const { user, queryClient } = await renderMembers({ backend });
        queryClient.setQueryData(["org", KILIMA, "organization"], orgDto());
        queryClient.setQueryData(["org", KILIMA, "teams", "list"], []);

        await openMenuFor(user, "Fatuma Hassan");
        await user.click(screen.getByRole("menuitem", { name: "Remove from Kilima Labs" }));
        const dialog = await screen.findByRole("dialog", { name: "Remove Fatuma Hassan?" });
        expect(dialog).toHaveTextContent("taken off every team");
        backend.members = backend.members.filter((member) => member.userId !== FATUMA.userId);
        await user.click(within(dialog).getByRole("button", { name: "Remove member" }));

        expect(await screen.findByText("Fatuma Hassan removed from Kilima Labs")).toBeInTheDocument();
        await waitFor(() => {
            expect(screen.queryByRole("row", { name: /Fatuma Hassan/ })).not.toBeInTheDocument();
        });
        expect(removed).toEqual([FATUMA.userId]);
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        expect(queryClient.getQueryState(["org", KILIMA, "organization"])?.isInvalidated).toBe(true);
        expect(queryClient.getQueryState(["org", KILIMA, "teams", "list"])?.isInvalidated).toBe(true);
    });

    it("keeps the dialog open with the reason when the removal is refused", async () => {
        replyToRemovals(() => error(409, "CONCURRENT_MODIFICATION", { message: "Concurrent modification" }));
        const { user } = await renderMembers();

        await openMenuFor(user, "Kevin Ochieng");
        await user.click(screen.getByRole("menuitem", { name: "Remove from Kilima Labs" }));
        const dialog = await screen.findByRole("dialog", { name: "Remove Kevin Ochieng?" });
        await user.click(within(dialog).getByRole("button", { name: "Remove member" }));

        expect(await within(dialog).findByRole("alert")).toHaveTextContent(
            "Someone else changed this membership at the same time. Try again.",
        );
        await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
        await openMenuFor(user, "Kevin Ochieng");
        await user.click(screen.getByRole("menuitem", { name: "Remove from Kilima Labs" }));

        expect(within(await screen.findByRole("dialog")).queryByRole("alert")).not.toBeInTheDocument();
    });

    it("closes quietly for someone who was already removed", async () => {
        replyToRemovals(() => error(404, "MEMBER_NOT_FOUND", { message: "Member not found." }));
        const { user } = await renderMembers();

        await openMenuFor(user, "Kevin Ochieng");
        await user.click(screen.getByRole("menuitem", { name: "Remove from Kilima Labs" }));
        await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Remove member" }));

        expect(await screen.findByText("Kevin Ochieng was already removed.")).toBeInTheDocument();
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });

    it("lets a member leave from their own row, then forgets the organization and goes to another", async () => {
        const removed = replyToRemovals();
        const { user, queryClient } = await renderMembers({ backend: new MembersBackend([...TEAM], FATUMA) });
        queryClient.setQueryData(["orgs", "mine"], { items: [] });
        queryClient.setQueryData(["org", KILIMA, "organization"], orgDto());
        queryClient.setQueryData(["org", KILIMA, "apps", "list"], []);

        const menu = await openMenuFor(user, "Fatuma Hassan");
        expect(
            within(menu)
                .getAllByRole("menuitem")
                .map((item) => item.textContent),
        ).toEqual(["Leave Kilima Labs"]);
        await user.click(within(menu).getByRole("menuitem", { name: "Leave Kilima Labs" }));
        const dialog = await screen.findByRole("dialog", { name: "Leave Kilima Labs?" });
        await user.click(within(dialog).getByRole("button", { name: "Leave organization" }));

        await waitFor(() => {
            expect(router.replace).toHaveBeenCalledExactlyOnceWith("/orgs");
        });
        expect(removed).toEqual([FATUMA.userId]);
        expect(await screen.findByText("You left Kilima Labs")).toBeInTheDocument();
        expect(queryClient.getQueryData(["org", KILIMA, "organization"])).toBeUndefined();
        expect(queryClient.getQueryData(["org", KILIMA, "apps", "list"])).toBeUndefined();
        expect(queryClient.getQueryState(["orgs", "mine"])?.isInvalidated).toBe(true);
    });

    it("never offers the owner a way to leave", async () => {
        await renderMembers();

        expect(screen.queryByRole("button", { name: "Actions for Amani Otieno" })).not.toBeInTheDocument();
    });
});
