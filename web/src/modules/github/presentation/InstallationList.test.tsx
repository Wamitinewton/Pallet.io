import { screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { GITHUB_PAGE, GitHubBackend, KEVIN, KILIMA, renderGitHub, WANJIRU } from "./testing/render-github";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router, usePathname: () => GITHUB_PAGE }));

async function openMenuFor(user: Awaited<ReturnType<typeof renderGitHub>>["user"], login: string) {
    await user.click(screen.getByRole("button", { name: `Actions for ${login}` }));
    return screen.findByRole("menu");
}

beforeEach(() => {
    vi.clearAllMocks();
});

describe("InstallationList", () => {
    it("lists each installation with its status, who linked it and when", async () => {
        await renderGitHub();

        const kilima = screen.getByRole("row", { name: /kilima-labs/ });
        expect(kilima).toHaveTextContent("Organization");
        expect(kilima).toHaveTextContent("Active");
        expect(kilima).toHaveTextContent("by Amani Otieno");
        const wanjiru = screen.getByRole("row", { name: /wanjiru-kamau/ });
        expect(wanjiru).toHaveTextContent("Personal account");
        expect(wanjiru).toHaveTextContent("Suspended");
        expect(wanjiru).toHaveTextContent("by a former member");
        expect(screen.getByText(/A suspended installation was paused on GitHub/)).toBeInTheDocument();
    });

    it("offers a viewer the way to GitHub but never removal", async () => {
        const { user } = await renderGitHub({ backend: new GitHubBackend(undefined, undefined, undefined, KEVIN) });

        const menu = await openMenuFor(user, "kilima-labs");

        expect(
            within(menu)
                .getAllByRole("menuitem")
                .map((item) => item.textContent),
        ).toEqual(["Manage on GitHub"]);
        expect(within(menu).getByRole("menuitem", { name: "Manage on GitHub" })).toHaveAttribute(
            "href",
            "https://github.com/organizations/kilima-labs/settings/installations/41000001",
        );
        expect(screen.queryByRole("button", { name: "Connect GitHub" })).not.toBeInTheDocument();
    });

    it("removes an installation once an admin confirms what it disconnects", async () => {
        const backend = new GitHubBackend(undefined, undefined, undefined, WANJIRU);
        const { user, queryClient } = await renderGitHub({ backend });
        queryClient.setQueryData(["org", KILIMA, "apps", "list"], []);

        await openMenuFor(user, "kilima-labs");
        await user.click(screen.getByRole("menuitem", { name: "Remove from Kilima Labs" }));
        const dialog = await screen.findByRole("dialog", { name: "Remove kilima-labs?" });
        expect(dialog).toHaveTextContent(
            "Apps in Kilima Labs that build from its repositories are disconnected, and pushes stop building until each one is linked again.",
        );
        expect(dialog).toHaveTextContent("The Pallet app stays installed on GitHub");
        await user.click(within(dialog).getByRole("button", { name: "Remove" }));

        expect(await screen.findByText("kilima-labs removed from Kilima Labs")).toBeInTheDocument();
        expect(backend.sent("unlink").map(({ installationId }) => installationId)).toEqual(["41000001"]);
        expect(screen.queryByRole("row", { name: /kilima-labs/ })).not.toBeInTheDocument();
        await waitFor(() => {
            expect(queryClient.getQueryState(["org", KILIMA, "apps", "list"])?.isInvalidated).toBe(true);
        });
    });

    it("treats an installation someone else removed first as removed", async () => {
        const backend = new GitHubBackend();
        const { user } = await renderGitHub({ backend });

        await openMenuFor(user, "wanjiru-kamau");
        await user.click(screen.getByRole("menuitem", { name: "Remove from Kilima Labs" }));
        backend.installations = backend.installations.filter((link) => link.accountLogin !== "wanjiru-kamau");
        await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Remove" }));

        expect(await screen.findByText("wanjiru-kamau was already removed from Kilima Labs")).toBeInTheDocument();
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        expect(screen.queryByRole("row", { name: /wanjiru-kamau/ })).not.toBeInTheDocument();
    });

    it("shows the empty state, with a way to connect for admins only", async () => {
        const { unmount } = await renderGitHub({ backend: new GitHubBackend([]) });

        expect(screen.getByRole("heading", { name: "GitHub isn't connected yet" })).toBeInTheDocument();
        expect(screen.getAllByRole("button", { name: "Connect GitHub" })).toHaveLength(2);
        unmount();

        await renderGitHub({ backend: new GitHubBackend([], undefined, undefined, KEVIN) });

        expect(screen.getByText(/An admin can install the Pallet GitHub App/)).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Connect GitHub" })).not.toBeInTheDocument();
    });
});
