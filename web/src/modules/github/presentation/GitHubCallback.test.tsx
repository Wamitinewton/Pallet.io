import { asInstallationId } from "@/shared/domain/ids";
import { browserReturnIntents, storedReturnIntent } from "@/test/github";
import { error } from "@/test/msw/envelopes";
import { renderWithProviders, TEST_NOW } from "@/test/render";
import { screen, waitFor } from "@testing-library/react";
import { StrictMode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { authorizeIntent, installIntent } from "../domain/return-intent";
import { GitHubCallback } from "./GitHubCallback";
import { AUTHORIZE_URL, GITHUB_PAGE, GitHubBackend, INSTALL_URL, KILIMA, seeded } from "./testing/render-github";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router, usePathname: () => "/github/callback" }));

const leaveForGitHub = vi.hoisted(() => vi.fn());
vi.mock("./leave-for-github", () => ({ leaveForGitHub }));

const CODE = "c0de1a2b3c";
const STATE = "v1.signed-state.s1gnature";
const NEW_INSTALLATION = 41000002;
const aMinuteAgo = new Date(Date.parse(TEST_NOW) - 60_000);

function arriveFromGitHub(query: string) {
    window.history.replaceState(null, "", `/github/callback?${query}`);
}

function renderCallback(backend = new GitHubBackend([]), { strict = false } = {}) {
    const queryClient = seeded(backend);
    const ui = strict ? (
        <StrictMode>
            <GitHubCallback />
        </StrictMode>
    ) : (
        <GitHubCallback />
    );
    return { ...renderWithProviders(ui, { queryClient }), backend };
}

beforeEach(() => {
    vi.clearAllMocks();
    window.history.replaceState(null, "", "/");
});

describe("GitHubCallback", () => {
    it("takes code and state off the address before the request leaves", async () => {
        browserReturnIntents.save(installIntent(aMinuteAgo, KILIMA, GITHUB_PAGE));
        arriveFromGitHub(
            `installation_id=${String(NEW_INSTALLATION)}&setup_action=install&code=${CODE}&state=${STATE}`,
        );

        const { backend } = renderCallback();

        await waitFor(() => {
            expect(backend.sent("link")).toHaveLength(1);
        });
        expect(backend.sent("link")[0]?.address).toBe("/github/callback");
        expect(window.location.search).toBe("");
        expect(document.body.textContent).not.toContain(CODE);
        expect(document.body.textContent).not.toContain(STATE);
    });

    it("links a fresh install with all three and goes back flagged as installed", async () => {
        browserReturnIntents.save(installIntent(aMinuteAgo, KILIMA, GITHUB_PAGE));
        arriveFromGitHub(
            `installation_id=${String(NEW_INSTALLATION)}&setup_action=install&code=${CODE}&state=${STATE}`,
        );

        const { backend } = renderCallback();

        await waitFor(() => {
            expect(router.replace).toHaveBeenCalledWith(`${GITHUB_PAGE}?github=installed`);
        });
        expect(backend.sent("link").map(({ body }) => body)).toEqual([
            { installationId: NEW_INSTALLATION, code: CODE, state: STATE },
        ]);
        expect(storedReturnIntent()).toBeUndefined();
    });

    it("completes an authorization and returns to the step that asked for it", async () => {
        browserReturnIntents.save(authorizeIntent(aMinuteAgo, `${GITHUB_PAGE}?connect=existing`));
        arriveFromGitHub(`code=${CODE}&state=${STATE}`);

        const { backend } = renderCallback();

        await waitFor(() => {
            expect(router.replace).toHaveBeenCalledWith(`${GITHUB_PAGE}?connect=existing&github=connected`);
        });
        expect(backend.sent("complete").map(({ body }) => body)).toEqual([{ code: CODE, state: STATE }]);
    });

    it("asks to start again without an intent, sending nothing", async () => {
        arriveFromGitHub(
            `installation_id=${String(NEW_INSTALLATION)}&setup_action=install&code=${CODE}&state=${STATE}`,
        );

        const { backend } = renderCallback();

        expect(
            await screen.findByRole("heading", { name: "Start again from your organization's GitHub page" }),
        ).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "Open Pallet" })).toHaveAttribute("href", "/orgs");
        expect(backend.requests).toEqual([]);
    });

    it("offers to restart the same install when the state is refused", async () => {
        browserReturnIntents.save(installIntent(aMinuteAgo, KILIMA, GITHUB_PAGE));
        arriveFromGitHub(
            `installation_id=${String(NEW_INSTALLATION)}&setup_action=install&code=${CODE}&state=${STATE}`,
        );
        const backend = new GitHubBackend([]);
        backend.replies.set("link", () => error(400, "INVALID_AUTHORIZATION_STATE", { message: "Invalid state." }));

        const { user } = renderCallback(backend);

        expect(await screen.findByRole("heading", { name: "That GitHub sign-in expired" })).toBeInTheDocument();
        expect(screen.getByText("That GitHub sign-in expired or was already used. Try again.")).toBeInTheDocument();
        expect(router.replace).not.toHaveBeenCalled();

        await user.click(screen.getByRole("button", { name: "Try again" }));

        await waitFor(() => {
            expect(leaveForGitHub).toHaveBeenCalledWith(INSTALL_URL);
        });
        expect(backend.sent("installSession")).toHaveLength(1);
        expect(storedReturnIntent()).toMatchObject({ kind: "install", orgId: KILIMA, returnTo: GITHUB_PAGE });
    });

    it("makes exactly one request when strict mode mounts it twice", async () => {
        browserReturnIntents.save(installIntent(aMinuteAgo, KILIMA, GITHUB_PAGE));
        arriveFromGitHub(
            `installation_id=${String(NEW_INSTALLATION)}&setup_action=install&code=${CODE}&state=${STATE}`,
        );

        const { backend } = renderCallback(new GitHubBackend([]), { strict: true });

        await waitFor(() => {
            expect(router.replace).toHaveBeenCalledWith(`${GITHUB_PAGE}?github=installed`);
        });
        expect(backend.sent("link")).toHaveLength(1);
        expect(router.replace).toHaveBeenCalledTimes(1);
    });

    it("waits for GitHub's admin when the install was only requested", async () => {
        browserReturnIntents.save(installIntent(aMinuteAgo, KILIMA, GITHUB_PAGE));
        arriveFromGitHub("setup_action=request");

        const { backend } = renderCallback();

        expect(await screen.findByRole("heading", { name: "Waiting for your GitHub admin" })).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "Back to GitHub settings" })).toHaveAttribute("href", GITHUB_PAGE);
        expect(backend.requests).toEqual([]);
    });

    it("says the authorization was cancelled", async () => {
        browserReturnIntents.save(authorizeIntent(aMinuteAgo, GITHUB_PAGE));
        arriveFromGitHub(`error=access_denied&state=${STATE}`);

        const { backend } = renderCallback();

        expect(await screen.findByRole("heading", { name: "GitHub authorization was cancelled" })).toBeInTheDocument();
        expect(backend.requests).toEqual([]);
    });

    it("refuses an installation id that isn't a positive integer", async () => {
        browserReturnIntents.save(installIntent(aMinuteAgo, KILIMA, GITHUB_PAGE));
        arriveFromGitHub(`installation_id=1e3&code=${CODE}&state=${STATE}`);

        const { backend } = renderCallback();

        expect(await screen.findByRole("heading", { name: "This link from GitHub isn't valid" })).toBeInTheDocument();
        expect(backend.requests).toEqual([]);
    });

    it("sends the person to authorize when an install came back without a session, then links it", async () => {
        browserReturnIntents.save(installIntent(aMinuteAgo, KILIMA, GITHUB_PAGE));
        arriveFromGitHub(`installation_id=${String(NEW_INSTALLATION)}&setup_action=install`);
        const backend = new GitHubBackend([]);
        backend.replies.set("link", () => error(403, "GITHUB_AUTHORIZATION_REQUIRED", { message: "Authorize first." }));

        renderCallback(backend);

        await waitFor(() => {
            expect(leaveForGitHub).toHaveBeenCalledWith(AUTHORIZE_URL);
        });
        expect(screen.getByRole("status", { name: "Signing you in to GitHub" })).toBeInTheDocument();
        expect(storedReturnIntent()).toMatchObject({
            kind: "authorize",
            orgId: KILIMA,
            installationId: asInstallationId(NEW_INSTALLATION),
        });
    });

    it("explains a refusal it can't recover from, with a way back", async () => {
        browserReturnIntents.save(installIntent(aMinuteAgo, KILIMA, GITHUB_PAGE));
        arriveFromGitHub(
            `installation_id=${String(NEW_INSTALLATION)}&setup_action=install&code=${CODE}&state=${STATE}`,
        );
        const backend = new GitHubBackend([]);
        backend.replies.set("link", () => error(409, "INSTALLATION_SUSPENDED", { message: "Suspended." }));

        renderCallback(backend);

        expect(await screen.findByRole("heading", { name: "GitHub couldn't be connected" })).toBeInTheDocument();
        expect(
            screen.getByText("The app is suspended on that GitHub account. Its owner can unsuspend it on GitHub."),
        ).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "Back to GitHub settings" })).toHaveAttribute("href", GITHUB_PAGE);
    });
});
