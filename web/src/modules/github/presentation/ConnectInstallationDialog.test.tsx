import { storedReturnIntent } from "@/test/github";
import { visibleInstallationDto } from "@/test/msw/git-integration";
import { screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
    AUTHORIZE_URL,
    GITHUB_PAGE,
    GitHubBackend,
    INSTALL_URL,
    KILIMA,
    KILIMA_LABS,
    renderGitHub,
    WANJIRU_ACCOUNT,
} from "./testing/render-github";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router, usePathname: () => GITHUB_PAGE }));

const leaveForGitHub = vi.hoisted(() => vi.fn());
vi.mock("./leave-for-github", () => ({ leaveForGitHub }));

const AMANI_VISIBLE = visibleInstallationDto({
    installationId: 41000002,
    accountLogin: "amani-otieno",
    accountType: "User",
});
const SUSPENDED_VISIBLE = visibleInstallationDto({
    installationId: 41000004,
    accountLogin: "jabali-tools",
    suspended: true,
});

async function openExistingStep(user: Awaited<ReturnType<typeof renderGitHub>>["user"]) {
    await user.click(screen.getByRole("button", { name: "Connect GitHub" }));
    const dialog = await screen.findByRole("dialog", { name: "Connect GitHub" });
    await user.click(within(dialog).getByRole("radio", { name: /Use an existing installation/ }));
    await user.click(within(dialog).getByRole("button", { name: "Continue" }));
    return screen.findByRole("dialog", { name: "Use an existing installation" });
}

beforeEach(() => {
    vi.clearAllMocks();
});

describe("ConnectInstallationDialog", () => {
    it("installs on a new account: records the intent, then leaves for GitHub in this tab", async () => {
        const { user, backend } = await renderGitHub();

        await user.click(screen.getByRole("button", { name: "Connect GitHub" }));
        await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Continue" }));
        const dialog = await screen.findByRole("dialog", { name: "Install the Pallet GitHub App" });
        expect(dialog).toHaveTextContent("read access to code and metadata");
        await user.click(within(dialog).getByRole("button", { name: "Continue to GitHub" }));

        await waitFor(() => {
            expect(leaveForGitHub).toHaveBeenCalledWith(INSTALL_URL);
        });
        expect(backend.sent("installSession")).toHaveLength(1);
        expect(storedReturnIntent()).toMatchObject({ kind: "install", orgId: KILIMA, returnTo: GITHUB_PAGE });
    });

    it("asks to sign in to GitHub first, coming back to this step", async () => {
        const { user, backend } = await renderGitHub({ backend: new GitHubBackend(undefined, null) });

        const dialog = await openExistingStep(user);
        expect(dialog).toHaveTextContent("Sign in to GitHub so Pallet can list the installations");
        await user.click(within(dialog).getByRole("button", { name: "Sign in with GitHub" }));

        await waitFor(() => {
            expect(leaveForGitHub).toHaveBeenCalledWith(AUTHORIZE_URL);
        });
        expect(backend.sent("visible")).toEqual([]);
        expect(storedReturnIntent()).toMatchObject({
            kind: "authorize",
            returnTo: `${GITHUB_PAGE}?connect=existing`,
        });
    });

    it("marks what's linked here and holds back what GitHub suspended", async () => {
        const backend = new GitHubBackend(undefined, undefined, [
            visibleInstallationDto(),
            AMANI_VISIBLE,
            SUSPENDED_VISIBLE,
        ]);
        const { user } = await renderGitHub({ backend });

        const dialog = await openExistingStep(user);

        const kilima = await within(dialog).findByRole("radio", { name: /kilima-labs/ });
        expect(kilima).toHaveTextContent("Linked here");
        expect(kilima).toBeEnabled();
        expect(within(dialog).getByRole("radio", { name: /amani-otieno/ })).toBeEnabled();
        const suspended = within(dialog).getByRole("radio", { name: /jabali-tools/ });
        expect(suspended).toHaveTextContent("Suspended on GitHub");
        expect(suspended).toBeDisabled();
    });

    it("links the chosen installation by id alone and lists it", async () => {
        const backend = new GitHubBackend([KILIMA_LABS], undefined, [visibleInstallationDto(), AMANI_VISIBLE]);
        const { user } = await renderGitHub({ backend });

        const dialog = await openExistingStep(user);
        await user.click(await within(dialog).findByRole("radio", { name: /amani-otieno/ }));
        await user.click(within(dialog).getByRole("button", { name: "Link installation" }));

        expect(await screen.findByText("amani-otieno linked to Kilima Labs")).toBeInTheDocument();
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        expect(backend.sent("link").map(({ body }) => body)).toEqual([{ installationId: 41000002 }]);
        expect(await screen.findByRole("row", { name: /amani-otieno/ })).toBeInTheDocument();
    });

    it("says so when the organization already had the installation", async () => {
        const backend = new GitHubBackend([KILIMA_LABS, WANJIRU_ACCOUNT]);
        const { user } = await renderGitHub({ backend });

        const dialog = await openExistingStep(user);
        await user.click(await within(dialog).findByRole("radio", { name: /kilima-labs/ }));
        await user.click(within(dialog).getByRole("button", { name: "Link installation" }));

        expect(await screen.findByText("kilima-labs was already linked to Kilima Labs")).toBeInTheDocument();
    });

    it("falls back to signing in when the GitHub session ended meanwhile", async () => {
        const backend = new GitHubBackend([KILIMA_LABS], undefined, [visibleInstallationDto(), AMANI_VISIBLE]);
        const { user } = await renderGitHub({ backend });

        const dialog = await openExistingStep(user);
        await within(dialog).findByRole("radio", { name: /amani-otieno/ });
        backend.session = null;
        await user.click(within(dialog).getByRole("radio", { name: /amani-otieno/ }));
        backend.replies.set("link", () =>
            Response.json(
                {
                    success: false,
                    message: "Authorize first.",
                    error: "GITHUB_AUTHORIZATION_REQUIRED",
                    statusCode: 403,
                },
                { status: 403 },
            ),
        );
        await user.click(within(dialog).getByRole("button", { name: "Link installation" }));

        expect(await within(dialog).findByRole("button", { name: "Sign in with GitHub" })).toBeInTheDocument();
    });

    it("opens on the existing installation step when a sign-in comes back to it", async () => {
        const urls: string[] = [];
        await renderGitHub({
            searchParams: "?connect=existing&github=connected",
            onUrlUpdate: ({ queryString }) => {
                urls.push(queryString);
            },
        });

        expect(await screen.findByRole("dialog", { name: "Use an existing installation" })).toBeInTheDocument();
        expect(screen.getByText("You're signed in to GitHub.")).toBeInTheDocument();
        await waitFor(() => {
            expect(urls.at(-1)).toBe("");
        });
    });
});
