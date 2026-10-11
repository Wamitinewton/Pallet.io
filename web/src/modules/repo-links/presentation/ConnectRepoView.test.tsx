import { storedReturnIntent } from "@/test/github";
import { error } from "@/test/msw/envelopes";
import { githubSessionDto, installationLinkDto } from "@/test/msw/git-integration";
import { screen, waitFor, within } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
    AMANI_ACCOUNT,
    APP_PAGE,
    AUTHORIZE_URL,
    CONNECT_PAGE,
    FATUMA,
    renderConnect,
    RepoLinksBackend,
} from "./testing/render-repo-links";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router, usePathname: () => CONNECT_PAGE }));

const leaveForGitHub = vi.hoisted(() => vi.fn());
vi.mock("@/modules/github/presentation/leave-for-github", () => ({ leaveForGitHub }));

const repositories = () => screen.getByRole("radiogroup", { name: "Repositories" });
const repository = (name: string) => within(repositories()).getByRole("radio", { name: new RegExp(name) });
const branch = () => screen.getByLabelText("Production branch");
const rootDirectory = () => screen.getByLabelText("Root directory");
const linkAndDeploy = () => screen.getByRole("button", { name: "Link and deploy" });
const linkOnly = () => screen.getByRole("button", { name: "Link only" });
const step = (name: string) => screen.getByRole("heading", { level: 2, name }).closest("section");

/** Not linked yet, signed in to GitHub, as an admin: the page ready to pick from. */
const unlinked = () => new RepoLinksBackend(null);

async function pick(user: UserEvent, name: string) {
    await user.click(await screen.findByRole("radio", { name: new RegExp(name) }));
}

beforeEach(() => {
    vi.clearAllMocks();
    sessionStorage.clear();
});

describe("ConnectRepoView", () => {
    it("asks for a GitHub sign-in first, coming back to this page", async () => {
        const backend = new RepoLinksBackend(null, undefined, null);
        const { user } = await renderConnect({ backend });

        expect(screen.getByRole("heading", { level: 1, name: "Link a repository to Checkout API" })).toBeVisible();
        expect(screen.getByRole("heading", { name: "Confirm who you are on GitHub" })).toBeInTheDocument();
        expect(screen.getByRole("heading", { name: "Choose a repository" })).toBeInTheDocument();
        expect(screen.queryByRole("radiogroup")).not.toBeInTheDocument();
        expect(backend.sent("repositories")).toEqual([]);

        await user.click(screen.getByRole("button", { name: "Continue with GitHub" }));

        await waitFor(() => {
            expect(leaveForGitHub).toHaveBeenCalledWith(AUTHORIZE_URL);
        });
        expect(storedReturnIntent()?.returnTo).toBe(CONNECT_PAGE);
    });

    it("lists what the caller's GitHub user reaches, holding back what can't be linked with the reason", async () => {
        const { backend } = await renderConnect({ backend: unlinked() });

        expect(step("GitHub account")).toHaveTextContent("Signed in as @amani-otieno");
        expect(screen.getByRole("combobox", { name: "GitHub account" })).toHaveValue("41000001");
        expect(backend.sent("repositories")[0]?.installationId).toBe("41000001");
        expect(repository("kilima-labs/checkout-api")).toBeEnabled();
        expect(repository("kilima-labs/infrastructure")).toBeDisabled();
        expect(repository("kilima-labs/infrastructure")).toHaveAccessibleDescription("Needs push access");
        expect(repository("kilima-labs/checkout-v1")).toBeDisabled();
        expect(repository("kilima-labs/checkout-v1")).toHaveAccessibleDescription(
            "Archived repositories can't be linked",
        );
        expect(screen.getByText(/Only repositories your own GitHub user can reach/)).toBeInTheDocument();
        expect(linkAndDeploy()).toBeDisabled();
    });

    it("fills the production branch from the chosen repository's default branch", async () => {
        const { user } = await renderConnect({ backend: unlinked() });

        await pick(user, "kilima-labs/web");

        expect(branch()).toHaveValue("develop");
        expect(step("Build settings")).toHaveTextContent("kilima-labs/web");
    });

    it("links and deploys with deployNow true, then opens the app", async () => {
        const { user, backend } = await renderConnect({ backend: unlinked() });

        await pick(user, "kilima-labs/web");
        await user.type(rootDirectory(), "apps/merchant/");
        await user.click(linkAndDeploy());

        expect(await screen.findByText("kilima-labs/web linked to Checkout API")).toBeInTheDocument();
        expect(JSON.parse(backend.sent("link")[0]?.raw ?? "{}")).toEqual({
            installationId: 41000001,
            repoId: 72000002,
            productionBranch: "develop",
            rootDirectory: "apps/merchant",
            autoDeploy: true,
            deployNow: true,
        });
        expect(router.push).toHaveBeenCalledWith(APP_PAGE);
    });

    it("links only with deployNow false, leaving the root directory out", async () => {
        const { user, backend } = await renderConnect({ backend: unlinked() });

        await pick(user, "kilima-labs/checkout-api");
        await user.click(screen.getByRole("switch", { name: "Build every push" }));
        await user.click(linkOnly());

        await waitFor(() => {
            expect(JSON.parse(backend.sent("link")[0]?.raw ?? "{}")).toEqual({
                installationId: 41000001,
                repoId: 72000001,
                productionBranch: "main",
                autoDeploy: false,
                deployNow: false,
            });
        });
    });

    it("reads the repositories of another account when it is chosen", async () => {
        const { user, backend } = await renderConnect({ backend: unlinked() });

        await user.selectOptions(
            screen.getByRole("combobox", { name: "GitHub account" }),
            String(AMANI_ACCOUNT.installationId),
        );

        await waitFor(() => {
            expect(backend.sent("repositories").at(-1)?.installationId).toBe("41000002");
        });
    });

    it("filters by name prefix once typing pauses", async () => {
        const { user, backend } = await renderConnect({ backend: unlinked() });

        await user.type(screen.getByRole("searchbox", { name: "Filter repositories" }), "web");

        await waitFor(() => {
            expect(backend.sent("repositories").at(-1)?.params?.get("q")).toBe("web");
        });
        await waitFor(() => {
            expect(within(repositories()).getAllByRole("radio")).toHaveLength(1);
        });
    });

    it("refuses a branch Git wouldn't accept before asking GitHub", async () => {
        const { user, backend } = await renderConnect({ backend: unlinked() });

        await pick(user, "kilima-labs/checkout-api");
        await user.clear(branch());
        await user.type(branch(), "-x");
        await user.click(linkAndDeploy());

        expect(await screen.findByText("Must not start with '-'.")).toBeInTheDocument();
        expect(backend.sent("link")).toEqual([]);
    });

    it("says on the branch field when the branch doesn't exist", async () => {
        const { user } = await renderConnect({ backend: unlinked() });

        await pick(user, "kilima-labs/checkout-api");
        await user.clear(branch());
        await user.type(branch(), "staging");
        await user.click(linkAndDeploy());

        expect(await screen.findByText("There's no branch named staging on GitHub.")).toBeInTheDocument();
    });

    it("puts the backend's root directory rule on its field", async () => {
        const backend = unlinked();
        backend.replies.set("link", () =>
            error(400, "INVALID_ROOT_DIRECTORY", { message: "rootDirectory must not contain control characters." }),
        );
        const { user } = await renderConnect({ backend });

        await pick(user, "kilima-labs/checkout-api");
        await user.click(linkAndDeploy());

        expect(await screen.findByText("rootDirectory must not contain control characters.")).toBeInTheDocument();
    });

    it.each([
        ["REPOSITORY_PERMISSION_TOO_LOW", "You need push access to that repository on GitHub to link it."],
        ["REPOSITORY_NOT_ACCESSIBLE", "Pallet can't reach that repository through this account any more."],
    ])("says %s on the picker and reads its rows again", async (code, words) => {
        const backend = unlinked();
        backend.replies.set("link", () => error(403, code));
        const { user } = await renderConnect({ backend });
        await pick(user, "kilima-labs/checkout-api");
        const reads = backend.sent("repositories").length;

        await user.click(linkAndDeploy());

        expect(await within(step("Choose a repository") ?? document.body).findByRole("alert")).toHaveTextContent(words);
        await waitFor(() => {
            expect(backend.sent("repositories").length).toBeGreaterThan(reads);
        });
    });

    it("sends the person to the app when it was linked meanwhile", async () => {
        const backend = unlinked();
        backend.replies.set("link", () => error(409, "REPO_LINK_EXISTS"));
        const { user } = await renderConnect({ backend });

        await pick(user, "kilima-labs/checkout-api");
        await user.click(linkAndDeploy());

        await waitFor(() => {
            expect(router.replace).toHaveBeenCalledWith(APP_PAGE);
        });
        expect(screen.getByText("This app already has a repository. Here it is.")).toBeInTheDocument();
    });

    it("goes back to the sign-in step when the GitHub session ended", async () => {
        const backend = unlinked();
        backend.replies.set("link", () => error(403, "GITHUB_AUTHORIZATION_REQUIRED"));
        const { user } = await renderConnect({ backend });

        await pick(user, "kilima-labs/checkout-api");
        await user.click(linkAndDeploy());

        expect(await screen.findByRole("heading", { name: "Confirm who you are on GitHub" })).toBeInTheDocument();
        expect(screen.getByRole("alert")).toHaveTextContent("Your GitHub sign-in ended. Sign in again to continue.");
    });

    it("goes back to the sign-in step when the picker finds the session gone", async () => {
        const backend = unlinked();
        backend.replies.set("repositories", () => error(403, "GITHUB_AUTHORIZATION_REQUIRED"));
        await renderConnect({ backend });

        expect(await screen.findByRole("heading", { name: "Confirm who you are on GitHub" })).toBeInTheDocument();
    });

    it("sends the person to connect GitHub when the organization has no installation", async () => {
        const backend = new RepoLinksBackend(null, undefined, githubSessionDto(), [
            installationLinkDto({ status: "SUSPENDED" }),
        ]);
        await renderConnect({ backend });

        expect(screen.getByRole("heading", { name: "GitHub isn't connected yet" })).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "Connect GitHub" })).toHaveAttribute("href", "/orgs/org-kilima/github");
        expect(backend.sent("repositories")).toEqual([]);
    });

    it("sends anyone but an admin to the app's page", async () => {
        await renderConnect({ backend: new RepoLinksBackend(null, FATUMA), redirects: true });

        await waitFor(() => {
            expect(router.replace).toHaveBeenCalledWith(APP_PAGE);
        });
        expect(screen.queryByRole("button", { name: "Link and deploy" })).not.toBeInTheDocument();
    });

    it("sends the person to the app's page when it already has an active link", async () => {
        await renderConnect({ backend: new RepoLinksBackend(), redirects: true });

        await waitFor(() => {
            expect(router.replace).toHaveBeenCalledWith(APP_PAGE);
        });
    });
});
