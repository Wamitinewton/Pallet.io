import { githubSessionDto } from "@/test/msw/git-integration";
import { screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { signedInUntilCopy } from "./github-copy";
import { AUTHORIZE_URL, GITHUB_PAGE, GitHubBackend, renderGitHub } from "./testing/render-github";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router, usePathname: () => GITHUB_PAGE }));

const leaveForGitHub = vi.hoisted(() => vi.fn());
vi.mock("./leave-for-github", () => ({ leaveForGitHub }));

const panel = () => screen.getByRole("region", { name: "Your GitHub sign-in" });

beforeEach(() => {
    vi.clearAllMocks();
});

describe("GitHubSessionPanel", () => {
    it("shows who is signed in to GitHub and until when", async () => {
        await renderGitHub();

        expect(panel()).toHaveTextContent("@amani-otieno");
        expect(panel()).toHaveTextContent(signedInUntilCopy("2026-01-15T13:00:00Z"));
    });

    it("signs out of GitHub", async () => {
        const { user, backend } = await renderGitHub();

        await user.click(within(panel()).getByRole("button", { name: "Sign out of GitHub" }));

        expect(await screen.findByText("Signed out of GitHub on Pallet")).toBeInTheDocument();
        expect(within(panel()).getByText("Not signed in to GitHub")).toBeInTheDocument();
        expect(backend.sent("endSession")).toHaveLength(1);
    });

    it("signs in to GitHub, coming back to this page", async () => {
        const { user } = await renderGitHub({ backend: new GitHubBackend(undefined, null) });

        await user.click(within(panel()).getByRole("button", { name: "Sign in with GitHub" }));

        await waitFor(() => {
            expect(leaveForGitHub).toHaveBeenCalledWith(AUTHORIZE_URL);
        });
    });

    it("reads the session again once it expires", async () => {
        const backend = new GitHubBackend(undefined, githubSessionDto({ expiresAt: "2026-01-15T12:00:00.050Z" }));
        await renderGitHub({ backend });
        backend.session = null;

        expect(await within(panel()).findByText("Not signed in to GitHub")).toBeInTheDocument();
        expect(backend.sent("session").length).toBeGreaterThanOrEqual(2);
    });
});
