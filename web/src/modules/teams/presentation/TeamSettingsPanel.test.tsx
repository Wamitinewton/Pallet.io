import { error } from "@/test/msw/envelopes";
import { orgDto } from "@/test/msw/org-team";
import { screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { teamKeys } from "./queries";
import { GRACE, KILIMA, PAYMENTS, PAYMENTS_ID, renderTeam, TeamsBackend } from "./testing/render-teams";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({
    useRouter: () => router,
    usePathname: () => `/orgs/org-kilima/teams/${PAYMENTS.id}`,
}));

type User = Awaited<ReturnType<typeof renderTeam>>["user"];

async function openDelete(user: User) {
    await user.click(screen.getByRole("button", { name: "Delete Payments" }));
    return screen.findByRole("dialog", { name: "Delete the Payments team?" });
}

beforeEach(() => {
    vi.clearAllMocks();
});

describe("TeamSettingsPanel", () => {
    it("renames the team, keeping its slug", async () => {
        const { user, backend } = await renderTeam();

        const name = screen.getByLabelText("Name");
        expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
        expect(screen.getByText(/The slug stays/)).toHaveTextContent("The slug stays payments.");
        await user.clear(name);
        await user.type(name, "Billing");
        await user.click(screen.getByRole("button", { name: "Save" }));

        expect(await screen.findByText("Team renamed")).toBeInTheDocument();
        expect(backend.sent("rename").map(({ body }) => body)).toEqual([{ name: "Billing" }]);
        expect(screen.getByRole("heading", { level: 1, name: "Billing" })).toBeInTheDocument();
        expect(screen.getByRole("heading", { level: 1 }).closest("header")).toHaveTextContent("payments");
        expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    });

    it("says what deleting costs: nobody's access, only the team", async () => {
        const { user } = await renderTeam();

        expect(screen.getByText(/The 2 people on it stay in Kilima Labs/)).toBeInTheDocument();
        const dialog = await openDelete(user);

        expect(dialog).toHaveTextContent(
            "Nobody loses access. The team's 2 people stay in Kilima Labs with the same roles. Its apps are left without a team.",
        );
    });

    it("deletes only once the team's name is typed exactly", async () => {
        const { user, backend } = await renderTeam();

        const dialog = await openDelete(user);
        const confirm = within(dialog).getByRole("button", { name: "Delete team" });
        const typed = within(dialog).getByLabelText(/to confirm/);

        expect(confirm).toBeDisabled();
        await user.type(typed, "payments");
        expect(confirm).toBeDisabled();
        await user.clear(typed);
        await user.type(typed, "Payments");
        expect(confirm).toBeEnabled();
        expect(backend.sent("delete")).toEqual([]);
    });

    it("deletes the team, goes to the teams page, and refreshes what the deletion changed", async () => {
        const { user, backend, queryClient, unmount } = await renderTeam();
        queryClient.setQueryData(["org", KILIMA, "organization"], orgDto());
        queryClient.setQueryData(["org", KILIMA, "apps", "list"], []);

        const dialog = await openDelete(user);
        await user.type(within(dialog).getByLabelText(/to confirm/), "Payments");
        await user.click(within(dialog).getByRole("button", { name: "Delete team" }));

        await waitFor(() => {
            expect(router.replace).toHaveBeenCalledExactlyOnceWith("/orgs/org-kilima/teams");
        });
        expect(backend.sent("delete")).toHaveLength(1);
        expect(await screen.findByText("Payments team deleted")).toBeInTheDocument();
        expect(queryClient.getQueryState(["org", KILIMA, "apps", "list"])?.isInvalidated).toBe(true);
        expect(queryClient.getQueryState(["org", KILIMA, "organization"])?.isInvalidated).toBe(true);
        expect(backend.sent("get")).toHaveLength(1);

        unmount();
        expect(queryClient.getQueryCache().findAll({ queryKey: teamKeys.team(KILIMA, PAYMENTS_ID) })).toEqual([]);
    });

    it("keeps the dialog open with the reason when the deletion is refused", async () => {
        const backend = new TeamsBackend();
        backend.replies.set("delete", () => error(403, "INSUFFICIENT_ROLE", { message: "Insufficient role" }));
        const { user } = await renderTeam({ backend });

        const dialog = await openDelete(user);
        await user.type(within(dialog).getByLabelText(/to confirm/), "Payments");
        await user.click(within(dialog).getByRole("button", { name: "Delete team" }));

        expect(await within(dialog).findByRole("alert")).toHaveTextContent("Only admins can delete a team.");
        expect(router.replace).not.toHaveBeenCalled();
    });

    it("draws the not-found state, with the way back, for a team that no longer exists", async () => {
        await renderTeam({ backend: new TeamsBackend([]) });

        expect(await screen.findByRole("heading", { name: "This team isn't available" })).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "Back to teams" })).toHaveAttribute("href", "/orgs/org-kilima/teams");
        expect(screen.queryByText(GRACE.email)).not.toBeInTheDocument();
    });
});
