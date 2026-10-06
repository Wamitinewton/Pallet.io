import { screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { GITHUB_PAGE, GitHubBackend, KEVIN, renderGitHub } from "./testing/render-github";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router, usePathname: () => GITHUB_PAGE }));

beforeEach(() => {
    vi.clearAllMocks();
});

describe("GitHubView", () => {
    it("says the account is connected when back from an install, then takes the flag off the address", async () => {
        const urls: string[] = [];
        await renderGitHub({
            searchParams: "?github=installed",
            onUrlUpdate: ({ queryString }) => {
                urls.push(queryString);
            },
        });

        const callout = screen.getByRole("status");
        expect(callout).toHaveTextContent("GitHub is connected.");
        expect(screen.getByRole("link", { name: "Link one now" })).toHaveAttribute("href", "/orgs/org-kilima/apps");
        await waitFor(() => {
            expect(urls.at(-1)).toBe("");
        });
        expect(screen.getByText("GitHub is connected.")).toBeInTheDocument();
    });

    it("ignores a flag it doesn't know", async () => {
        await renderGitHub({ searchParams: "?github=pwned" });

        expect(screen.queryByText(/GitHub is connected/)).not.toBeInTheDocument();
    });

    it("never opens the connect dialog for someone who can't connect", async () => {
        await renderGitHub({
            backend: new GitHubBackend(undefined, undefined, undefined, KEVIN),
            searchParams: "?connect=existing",
        });

        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
});
