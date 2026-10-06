import { error } from "@/test/msw/envelopes";
import { memberDto } from "@/test/msw/org-team";
import { screen, waitFor, within } from "@testing-library/react";
import type { OnUrlUpdateFunction } from "nuqs/adapters/testing";
import { describe, expect, it, vi } from "vitest";
import { FATUMA, KEVIN, MembersBackend, renderMembers, TEAM, WANJIRU } from "./testing/render-members";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router, usePathname: () => "/orgs/org-kilima/members" }));

const rows = () =>
    within(screen.getByRole("table", { name: "Members" }))
        .getAllByRole("row")
        .slice(1);
const emails = () => rows().map((row) => within(row).getByText(/@/).textContent);
const rowOf = (name: string) => screen.getByRole("row", { name: new RegExp(name) });

function urlUpdates() {
    const updates: string[] = [];
    const onUrlUpdate: OnUrlUpdateFunction = ({ queryString }) => {
        updates.push(queryString);
    };
    return { updates, onUrlUpdate };
}

describe("MembersView", () => {
    it("lists everyone with their role and when they joined, and marks the caller", async () => {
        await renderMembers();

        expect(emails()).toEqual(TEAM.map((member) => member.email));
        expect(within(rowOf("Amani Otieno")).getByText("you")).toBeInTheDocument();
        expect(within(rowOf("Amani Otieno")).getByText("Owner")).toBeInTheDocument();
        expect(within(rowOf("Fatuma Hassan")).getByText("Developer")).toBeInTheDocument();
        expect(within(rowOf("Fatuma Hassan")).queryByText("you")).not.toBeInTheDocument();
        expect(screen.getByText("5 members")).toBeInTheDocument();
        expect(screen.getByText("Kilima Labs has one owner. Hand over ownership from a member's menu.")).toBeVisible();
        expect(screen.getByRole("columnheader", { name: "Name" })).toHaveAttribute("aria-sort", "ascending");
    });

    it("asks for the list the URL describes", async () => {
        const { backend } = await renderMembers({
            searchParams: "?q=wan&role=ADMIN&sort=joinedAt,desc&page=2&size=10",
        });

        const [request] = backend.listRequests;
        expect(Object.fromEntries(request ?? [])).toEqual({
            q: "wan",
            role: "ADMIN",
            sort: "joinedAt,desc",
            page: "1",
            size: "10",
            status: "ACTIVE",
        });
        expect(screen.getByRole("searchbox", { name: "Search members" })).toHaveValue("wan");
        expect(screen.getByRole("combobox", { name: "Role" })).toHaveValue("ADMIN");
        expect(screen.getByRole("combobox", { name: "Sort" })).toHaveValue("joinedAt,desc");
    });

    it("drops a sort the backend would refuse before asking", async () => {
        const { backend } = await renderMembers({ searchParams: "?sort=email,asc" });

        expect(backend.listed("sort")).toEqual(["displayName,asc"]);
        expect(screen.getByRole("combobox", { name: "Sort" })).toHaveValue("displayName,asc");
    });

    it("puts each filter in the URL and goes back to the first page when one changes", async () => {
        const { updates, onUrlUpdate } = urlUpdates();
        const { user, backend } = await renderMembers({ searchParams: "?page=2&size=10", onUrlUpdate });

        await user.selectOptions(screen.getByRole("combobox", { name: "Role" }), "DEVELOPER");

        await waitFor(() => {
            expect(updates.at(-1)).toBe("?size=10&role=DEVELOPER");
        });
        expect(await screen.findByText("Fatuma Hassan")).toBeInTheDocument();
        expect(backend.listRequests.at(-1)?.get("page")).toBe("0");

        await user.selectOptions(screen.getByRole("combobox", { name: "Sort" }), "Newest first");

        await waitFor(() => {
            expect(updates.at(-1)).toBe("?size=10&role=DEVELOPER&sort=joinedAt,desc");
        });
    });

    it("sends one search once typing pauses, not one per key", async () => {
        const { updates, onUrlUpdate } = urlUpdates();
        const { user, backend } = await renderMembers({ onUrlUpdate });

        await user.type(screen.getByRole("searchbox", { name: "Search members" }), "wan");

        await waitFor(() => {
            expect(emails()).toEqual([WANJIRU.email]);
        });
        expect(backend.listed("q")).toEqual([null, "wan"]);
        expect(updates).toEqual(["?q=wan"]);
    });

    it("hides the status filter from a developer, and never asks them for removed members", async () => {
        const { backend } = await renderMembers({
            backend: new MembersBackend([...TEAM], FATUMA),
            searchParams: "?status=REMOVED",
        });

        expect(screen.queryByRole("radiogroup", { name: "Status" })).not.toBeInTheDocument();
        expect(backend.listed("status")).toEqual(["ACTIVE"]);
    });

    it("lets an admin see who was removed", async () => {
        const removed = memberDto({
            userId: "user-otieno",
            displayName: "Otieno Juma",
            role: "DEVELOPER",
            status: "REMOVED",
        });
        const { user, backend } = await renderMembers({ backend: new MembersBackend([...TEAM, removed], WANJIRU) });

        await user.click(
            within(screen.getByRole("radiogroup", { name: "Status" })).getByRole("radio", { name: "Removed" }),
        );

        expect(await screen.findByText("Otieno Juma")).toBeInTheDocument();
        expect(screen.getByText("1 removed member")).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Actions for Otieno Juma" })).not.toBeInTheDocument();
        expect(backend.listed("status")).toEqual(["ACTIVE", "REMOVED"]);
    });

    it("says when nobody matches, and clears the filters", async () => {
        const { user } = await renderMembers({ searchParams: "?q=zawadi&role=VIEWER" });

        expect(screen.getByRole("heading", { name: "No members match" })).toBeInTheDocument();
        expect(screen.getByText("Nobody's name or email starts with “zawadi”.")).toBeInTheDocument();

        await user.click(screen.getByRole("button", { name: "Clear filters" }));

        expect(await screen.findByText("Kevin Ochieng")).toBeInTheDocument();
        expect(screen.getByRole("searchbox", { name: "Search members" })).toHaveValue("");
        expect(screen.getByRole("combobox", { name: "Role" })).toHaveValue("");
    });

    it("pages through the list from the URL", async () => {
        const viewers = Array.from({ length: 20 }, (_, index) =>
            memberDto({ userId: `user-v${String(index)}`, displayName: `Viewer ${String(index)}`, role: "VIEWER" }),
        );
        const { updates, onUrlUpdate } = urlUpdates();
        const { user, backend } = await renderMembers({
            backend: new MembersBackend([...TEAM, ...viewers]),
            searchParams: "?size=10",
            onUrlUpdate,
        });

        expect(screen.getByText("Page 1 of 3")).toBeInTheDocument();
        expect(screen.getByText("25 members")).toBeInTheDocument();
        await user.click(screen.getByRole("button", { name: "Next" }));

        expect(await screen.findByText("Page 2 of 3")).toBeInTheDocument();
        expect(rows()).toHaveLength(10);
        expect(backend.listed("page")).toEqual(["0", "1"]);
        expect(updates).toEqual(["?size=10&page=2"]);
    });

    it("offers the first page when the URL points past the last one", async () => {
        const { user } = await renderMembers({ searchParams: "?page=9" });

        expect(screen.getByRole("heading", { name: "There's nothing on this page" })).toBeInTheDocument();
        await user.click(screen.getByRole("button", { name: "Go to the first page" }));

        expect(await screen.findByText(KEVIN.displayName)).toBeInTheDocument();
    });

    it("shows a failed list with the reference and tries again", async () => {
        const backend = new MembersBackend();
        backend.listFailure = () => error(503, "SERVICE_UNAVAILABLE");
        const { user } = await renderMembers({ backend });

        const alert = screen.getByRole("alert");
        expect(alert).toHaveTextContent("Pallet is temporarily unavailable.");
        expect(alert).toHaveTextContent("Reference corr-test-0001");

        backend.listFailure = undefined;
        await user.click(within(alert).getByRole("button", { name: "Try again" }));

        expect(await screen.findByText(KEVIN.displayName)).toBeInTheDocument();
    });

    describe("with the invites tab", () => {
        const invites = { action: <button type="button">Invite people</button>, panel: <p>Pending invites</p> };

        it("puts members and invites on tabs for someone who manages invites, and keeps the tab in the URL", async () => {
            const { updates, onUrlUpdate } = urlUpdates();
            const { user } = await renderMembers({
                backend: new MembersBackend([...TEAM], WANJIRU),
                invites,
                onUrlUpdate,
            });

            const tabs = screen.getByRole("tablist", { name: "Members and invites" });
            expect(within(tabs).getByRole("tab", { name: "Members" })).toHaveAttribute("aria-selected", "true");
            expect(screen.getByRole("button", { name: "Invite people" })).toBeInTheDocument();

            await user.click(within(tabs).getByRole("tab", { name: "Invites" }));

            expect(await screen.findByText("Pending invites")).toBeInTheDocument();
            expect(screen.queryByRole("table", { name: "Members" })).not.toBeInTheDocument();
            await waitFor(() => {
                expect(updates.at(-1)).toBe("?tab=invites");
            });
        });

        it("opens on the invites tab without asking for members", async () => {
            const { backend } = await renderMembers({ invites, searchParams: "?tab=invites" });

            expect(screen.getByRole("tab", { name: "Invites" })).toHaveAttribute("aria-selected", "true");
            expect(screen.getByText("Pending invites")).toBeInTheDocument();
            expect(backend.listRequests).toEqual([]);
        });

        it.each([
            ["a developer", { backend: new MembersBackend([...TEAM], FATUMA) }],
            ["the owner of a personal organization", { orgKind: "PERSONAL" as const }],
        ])("shows %s the members list alone, whatever the URL says", async (_, scenario) => {
            await renderMembers({ ...scenario, invites, searchParams: "?tab=invites" });

            expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
            expect(screen.queryByText("Pending invites")).not.toBeInTheDocument();
            expect(screen.queryByRole("button", { name: "Invite people" })).not.toBeInTheDocument();
            expect(screen.getByRole("table", { name: "Members" })).toBeInTheDocument();
        });
    });
});
