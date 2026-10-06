import { error } from "@/test/msw/envelopes";
import { orgDto } from "@/test/msw/org-team";
import { screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { anApp } from "../domain/testing/fixtures";
import { appKeys } from "./queries";
import { AppsBackend, CHECKOUT, CHECKOUT_ID, FATUMA, KILIMA, renderApp, WANJIRU } from "./testing/render-apps";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({
    useRouter: () => router,
    usePathname: () => `/orgs/org-kilima/apps/${CHECKOUT.id}`,
}));

type User = Awaited<ReturnType<typeof renderApp>>["user"];

async function openDelete(user: User) {
    await user.click(screen.getByRole("button", { name: "Delete Checkout API" }));
    return screen.findByRole("dialog", { name: "Delete Checkout API?" });
}

beforeEach(() => {
    vi.clearAllMocks();
});

describe("DeleteAppPanel", () => {
    it("is offered to an admin", async () => {
        await renderApp({ backend: new AppsBackend(undefined, undefined, WANJIRU) });

        expect(screen.getByRole("button", { name: "Delete Checkout API" })).toBeInTheDocument();
    });

    it("is absent for a developer, who can still change the app", async () => {
        await renderApp({ backend: new AppsBackend(undefined, undefined, FATUMA) });

        expect(screen.getByRole("region", { name: "General" })).toBeInTheDocument();
        expect(screen.queryByRole("region", { name: "Delete app" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Delete Checkout API" })).not.toBeInTheDocument();
    });

    it("deletes only once the app's slug is typed exactly", async () => {
        const { user, backend } = await renderApp();

        const dialog = await openDelete(user);
        const confirm = within(dialog).getByRole("button", { name: "Delete app" });
        const typed = within(dialog).getByLabelText(/to confirm/);

        expect(within(dialog).getByText("checkout-api")).toBeInTheDocument();
        await user.type(typed, "Checkout API");
        expect(confirm).toBeDisabled();
        await user.clear(typed);
        await user.type(typed, "checkout-api");
        expect(confirm).toBeEnabled();
        expect(backend.sent("delete")).toEqual([]);
    });

    it("deletes the app, goes to the apps page, and drops or refreshes what the deletion changed", async () => {
        const { user, backend, queryClient, unmount } = await renderApp();
        queryClient.setQueryData(["org", KILIMA, "organization"], orgDto());
        queryClient.setQueryData([...appKeys.app(KILIMA, CHECKOUT_ID), "repo-link"], { linked: true });
        const listKey = [...appKeys.lists(KILIMA), "seeded"];
        queryClient.setQueryData(listKey, {
            items: [anApp()],
            page: 0,
            size: 20,
            totalItems: 1,
            totalPages: 1,
            isFirst: true,
            isLast: true,
        });

        const dialog = await openDelete(user);
        await user.type(within(dialog).getByLabelText(/to confirm/), "checkout-api");
        await user.click(within(dialog).getByRole("button", { name: "Delete app" }));

        await waitFor(() => {
            expect(router.replace).toHaveBeenCalledExactlyOnceWith("/orgs/org-kilima/apps");
        });
        expect(backend.sent("delete")).toHaveLength(1);
        expect(await screen.findByText("Checkout API deleted")).toBeInTheDocument();
        expect(queryClient.getQueryData(listKey)).toMatchObject({ items: [], totalItems: 0 });
        expect(queryClient.getQueryState(listKey)?.isInvalidated).toBe(true);
        expect(queryClient.getQueryState(["org", KILIMA, "organization"])?.isInvalidated).toBe(true);
        expect(backend.sent("get")).toHaveLength(1);

        unmount();
        expect(queryClient.getQueryCache().findAll({ queryKey: appKeys.app(KILIMA, CHECKOUT_ID) })).toEqual([]);
    });

    it("keeps the dialog open with the reason when the deletion is refused", async () => {
        const backend = new AppsBackend();
        backend.replies.set("delete", () => error(403, "INSUFFICIENT_ROLE", { message: "Insufficient role" }));
        const { user } = await renderApp({ backend });

        const dialog = await openDelete(user);
        await user.type(within(dialog).getByLabelText(/to confirm/), "checkout-api");
        await user.click(within(dialog).getByRole("button", { name: "Delete app" }));

        expect(await within(dialog).findByRole("alert")).toHaveTextContent("Only admins can delete an app.");
        expect(router.replace).not.toHaveBeenCalled();
    });

    it("draws the not-found state, with the way back, for an app that no longer exists", async () => {
        await renderApp({ backend: new AppsBackend([]) });

        expect(await screen.findByRole("heading", { name: "This app isn't available" })).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "Back to apps" })).toHaveAttribute("href", "/orgs/org-kilima/apps");
    });
});
