import { error } from "@/test/msw/envelopes";
import { acceptedHeadDto, disconnectedRepoLinkDto, repoLinkDto } from "@/test/msw/git-integration";
import { screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
    APP_PAGE,
    AUTHORIZE_URL,
    CONNECT_PAGE,
    FATUMA,
    KEVIN,
    renderAppPage,
    RepoLinksBackend,
    WANJIRU,
} from "./testing/render-repo-links";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router, usePathname: () => APP_PAGE }));

const leaveForGitHub = vi.hoisted(() => vi.fn());
vi.mock("@/modules/github/presentation/leave-for-github", () => ({ leaveForGitHub }));

const repository = () => screen.getByRole("region", { name: "Repository" });
const access = () => screen.getByRole("region", { name: "GitHub access" });
const settings = () => screen.getByRole("region", { name: "Build settings" });

beforeEach(() => {
    vi.clearAllMocks();
});

describe("RepositoryPanel", () => {
    it("shows a connected link: the repository, its health, the last accepted push and who vouches for it", async () => {
        await renderAppPage();

        expect(within(repository()).getByRole("link", { name: "kilima-labs/checkout-api" })).toHaveAttribute(
            "href",
            "https://github.com/kilima-labs/checkout-api",
        );
        expect(repository()).toHaveTextContent("Through the kilima-labs GitHub installation");
        expect(within(repository()).getByText("Connected")).toBeInTheDocument();
        expect(within(repository()).getByText("3f9c2e1")).toBeInTheDocument();
        expect(within(repository()).getByText("Retry M-Pesa callbacks on gateway timeout")).toHaveAttribute(
            "title",
            "Retry M-Pesa callbacks on gateway timeout",
        );
        expect(within(repository()).getByText("fatuma-hassan")).toBeInTheDocument();
        expect(within(repository()).getByText("main")).toBeInTheDocument();
        expect(within(repository()).getByText("14 minutes ago")).toBeInTheDocument();
        expect(access()).toHaveTextContent("Linked by Amani Otieno");
        expect(access()).toHaveTextContent("As @amani-otieno, who has admin access to this repository.");
        expect(access()).toHaveTextContent("10 January 2026");
        expect(screen.getByRole("button", { name: "Start a build" })).toBeInTheDocument();
        expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    });

    it("shows a head stored before its commit details were kept by its SHA, branch and time alone", async () => {
        await renderAppPage({
            backend: new RepoLinksBackend(
                repoLinkDto({ lastAcceptedHead: acceptedHeadDto({ message: null, author: null }) }),
            ),
        });

        expect(within(repository()).getByText("3f9c2e1")).toBeInTheDocument();
        expect(within(repository()).getByText("14 minutes ago")).toBeInTheDocument();
        expect(within(repository()).queryByText("Retry M-Pesa callbacks on gateway timeout")).not.toBeInTheDocument();
        expect(within(repository()).queryByText("fatuma-hassan")).not.toBeInTheDocument();
    });

    it("renders a commit message as text, never as markup", async () => {
        const message = "<img src=x onerror=alert(1)> Fix login";
        await renderAppPage({
            backend: new RepoLinksBackend(repoLinkDto({ lastAcceptedHead: acceptedHeadDto({ message }) })),
        });

        expect(within(repository()).getByText(message)).toBeInTheDocument();
        expect(within(repository()).queryByRole("img")).not.toBeInTheDocument();
    });

    it("warns, in amber, that an archived repository is read-only while it stays connected", async () => {
        await renderAppPage({ backend: new RepoLinksBackend(repoLinkDto({ warnings: ["REPOSITORY_ARCHIVED"] })) });

        expect(screen.getByText("This repository is archived on GitHub.")).toBeInTheDocument();
        expect(within(repository()).getByText("Connected")).toBeInTheDocument();
    });

    it("says why a link was disconnected and offers what mends it", async () => {
        await renderAppPage({ backend: new RepoLinksBackend(disconnectedRepoLinkDto("REPOSITORY_ACCESS_REMOVED")) });

        const alert = screen.getByRole("alert");
        expect(alert).toHaveTextContent("Pallet can't build from kilima-labs/checkout-api.");
        expect(alert).toHaveTextContent("The Pallet GitHub App no longer has access to it.");
        expect(alert).toHaveTextContent("Disconnected yesterday.");
        expect(within(alert).getByRole("link", { name: "Update access on GitHub" })).toHaveAttribute(
            "href",
            "https://github.com/organizations/kilima-labs/settings/installations/41000001",
        );
        expect(within(alert).getByRole("link", { name: "Link again" })).toHaveAttribute("href", CONNECT_PAGE);
        expect(within(repository()).getByText("Disconnected")).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Start a build" })).not.toBeInTheDocument();
        expect(screen.queryByRole("region", { name: "Unlink repository" })).not.toBeInTheDocument();
        expect(within(settings()).getByLabelText("Production branch")).toBeDisabled();
        expect(within(access()).queryByRole("button", { name: "Take over verification" })).not.toBeInTheDocument();
    });

    it.each([
        ["INSTALLATION_UNLINKED", "was removed from Kilima Labs", "Open GitHub settings"],
        ["INSTALLATION_DELETED", "was uninstalled from the account that owns it", "Open GitHub settings"],
        ["REPOSITORY_DELETED", "It was deleted on GitHub.", undefined],
        ["VERIFIER_ACCESS_LOST", "@amani-otieno, who vouched for it, no longer has push access", undefined],
    ])("explains %s in plain words", async (reason, words, action) => {
        await renderAppPage({ backend: new RepoLinksBackend(disconnectedRepoLinkDto(reason)) });

        const alert = screen.getByRole("alert");
        expect(alert).toHaveTextContent(words);
        if (action !== undefined) expect(within(alert).getByRole("link", { name: action })).toBeInTheDocument();
        expect(within(alert).getByRole("link", { name: "Link again" })).toBeInTheDocument();
    });

    it("marks the last access check as failed when the verifier lost access", async () => {
        await renderAppPage({ backend: new RepoLinksBackend(disconnectedRepoLinkDto("VERIFIER_ACCESS_LOST")) });

        expect(access()).toHaveTextContent("Failed 4 hours ago");
    });

    it("tells someone who can't relink that an admin can", async () => {
        await renderAppPage({
            backend: new RepoLinksBackend(disconnectedRepoLinkDto("REPOSITORY_DELETED"), FATUMA),
        });

        expect(screen.getByRole("alert")).toHaveTextContent("An admin can link it again.");
        expect(screen.queryByRole("link", { name: "Link again" })).not.toBeInTheDocument();
    });

    it("invites an admin to link a repository when there is none", async () => {
        await renderAppPage({ backend: new RepoLinksBackend(null) });

        expect(screen.getByRole("heading", { name: "Link a repository to start building" })).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "Link a repository" })).toHaveAttribute("href", CONNECT_PAGE);
        expect(screen.queryByRole("button", { name: "Start a build" })).not.toBeInTheDocument();
    });

    it("tells anyone else there is no repository yet", async () => {
        await renderAppPage({ backend: new RepoLinksBackend(null, KEVIN) });

        expect(screen.getByRole("heading", { name: "No repository yet" })).toBeInTheDocument();
        expect(screen.queryByRole("link", { name: "Link a repository" })).not.toBeInTheDocument();
    });

    it("shows a repository an admin unlinked as no repository, naming what it was", async () => {
        await renderAppPage({ backend: new RepoLinksBackend(disconnectedRepoLinkDto("UNLINKED_BY_USER")) });

        expect(screen.getByRole("heading", { name: "Link a repository to start building" })).toBeInTheDocument();
        expect(screen.getByText(/kilima-labs\/checkout-api was unlinked/)).toBeInTheDocument();
        expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    });

    it("shows a failed read as an error, never as a disconnected link", async () => {
        const backend = new RepoLinksBackend();
        backend.replies.set("get", () => error(500, "INTERNAL_ERROR"));
        await renderAppPage({ backend });

        expect(screen.getByRole("heading", { name: "Couldn't load this" })).toBeInTheDocument();
        expect(screen.queryByText("Disconnected")).not.toBeInTheDocument();
        expect(screen.queryByRole("heading", { name: "Link a repository to start building" })).not.toBeInTheDocument();
    });

    it("unlinks after confirmation and goes back to no repository", async () => {
        const { user, backend } = await renderAppPage();

        await user.click(screen.getByRole("button", { name: "Unlink repository" }));
        const dialog = await screen.findByRole("dialog", { name: "Unlink kilima-labs/checkout-api?" });
        expect(dialog).toHaveTextContent("Pushes to main will stop building Checkout API.");
        await user.click(within(dialog).getByRole("button", { name: "Unlink repository" }));

        expect(await screen.findByText("Repository unlinked")).toBeInTheDocument();
        expect(await screen.findByRole("heading", { name: "Link a repository to start building" })).toBeInTheDocument();
        expect(backend.sent("disconnect")).toHaveLength(1);
    });

    it("offers unlinking to admins only", async () => {
        await renderAppPage({ backend: new RepoLinksBackend(undefined, FATUMA) });

        expect(screen.queryByRole("region", { name: "Unlink repository" })).not.toBeInTheDocument();
        expect(within(access()).queryByRole("button", { name: "Take over verification" })).not.toBeInTheDocument();
    });

    it("takes over verification with the caller's GitHub session", async () => {
        const backend = new RepoLinksBackend(
            repoLinkDto({
                verification: {
                    verifiedByUserId: WANJIRU.userId,
                    githubLogin: "wanjiru-kamau",
                    permission: "push",
                    accessVerifiedAt: "2026-01-10T09:00:00Z",
                    accessCheckedAt: "2026-01-15T08:00:00Z",
                },
            }),
        );
        const { user } = await renderAppPage({ backend });
        expect(access()).toHaveTextContent("Linked by Wanjiru Kamau");

        await user.click(within(access()).getByRole("button", { name: "Take over verification" }));

        expect(await screen.findByText("You now vouch for kilima-labs/checkout-api")).toBeInTheDocument();
        expect(access()).toHaveTextContent("Linked by Amani Otieno");
        expect(backend.sent("takeOver")).toHaveLength(1);
    });

    it("signs in with GitHub first when there is no session, coming back to the app", async () => {
        const backend = new RepoLinksBackend(undefined, undefined, null);
        const { user } = await renderAppPage({ backend });

        expect(access()).toHaveTextContent("You'll sign in with GitHub first");
        await user.click(within(access()).getByRole("button", { name: "Take over verification" }));

        await waitFor(() => {
            expect(leaveForGitHub).toHaveBeenCalledWith(AUTHORIZE_URL);
        });
        expect(backend.sent("takeOver")).toHaveLength(0);
    });

    it("says why a take-over was refused, changing nothing", async () => {
        const backend = new RepoLinksBackend();
        backend.replies.set("takeOver", () => error(403, "REPOSITORY_PERMISSION_TOO_LOW"));
        const { user } = await renderAppPage({ backend });

        await user.click(within(access()).getByRole("button", { name: "Take over verification" }));

        expect(await within(access()).findByRole("alert")).toHaveTextContent(
            "Your GitHub user has less than push access to this repository",
        );
        expect(access()).toHaveTextContent("Linked by Amani Otieno");
    });
});
