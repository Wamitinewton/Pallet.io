import { ApiError, NetworkError, SessionExpiredError } from "@/shared/domain/errors";
import { asOrgId } from "@/shared/domain/ids";
import { manualClock } from "@/test/clock";
import { describe, expect, it } from "vitest";
import type { CallbackQuery } from "../domain/callback";
import {
    GITHUB_AUTHORIZATION_REQUIRED,
    INSTALLATION_NOT_ACCESSIBLE,
    INVALID_AUTHORIZATION_STATE,
} from "../domain/github-errors";
import { RETURN_INTENT_TTL_MS } from "../domain/return-intent";
import { aHandoff, AMANI_INSTALLATION, anInstallationLink, KILIMA_LABS_INSTALLATION } from "../domain/testing/fixtures";
import {
    InMemoryGitHubSessionRepository,
    InMemoryInstallationRepository,
    InMemoryReturnIntentStore,
} from "./testing/in-memory";
import { makeGitHubUseCases } from "./use-cases";

const KILIMA = asOrgId("org-kilima");
const GITHUB_PAGE = "/orgs/org-kilima/github";
const grant = { code: "a1b2c3", state: "signed-state" };

const refusal = (status: number, code: string) => new ApiError(status, code, "Refused", [], {}, undefined);

function setUp() {
    const clock = manualClock("2026-01-15T12:00:00Z");
    const sessions = new InMemoryGitHubSessionRepository();
    const installations = new InMemoryInstallationRepository([anInstallationLink()]);
    const intents = new InMemoryReturnIntentStore();
    const useCases = makeGitHubUseCases({ sessions, installations, intents, clock });
    return { clock, sessions, installations, intents, useCases };
}

const authorization: CallbackQuery = { ...grant };
const freshInstall: CallbackQuery = { installation_id: String(AMANI_INSTALLATION), setup_action: "install", ...grant };
const installWithoutAuthorization: CallbackQuery = {
    installation_id: String(AMANI_INSTALLATION),
    setup_action: "install",
};

describe("finishGitHubRedirect", () => {
    it("completes an authorization and returns to where it started, flagged as connected", async () => {
        const { sessions, intents, useCases } = setUp();
        await useCases.startAuthorization({ returnTo: `${GITHUB_PAGE}?connect=existing` });

        const outcome = await useCases.finishGitHubRedirect(authorization);

        expect(outcome).toEqual({ kind: "connected", destination: `${GITHUB_PAGE}?connect=existing&github=connected` });
        expect(sessions.completed).toEqual([grant]);
        expect(intents.stored).toBeUndefined();
    });

    it("links a fresh install to the organization the intent recorded", async () => {
        const { installations, intents, useCases } = setUp();
        await useCases.startInstall({ orgId: KILIMA, returnTo: GITHUB_PAGE });

        const outcome = await useCases.finishGitHubRedirect(freshInstall);

        expect(outcome).toEqual({
            kind: "installed",
            orgId: KILIMA,
            destination: `${GITHUB_PAGE}?github=installed`,
            alreadyLinked: false,
        });
        expect(installations.links).toEqual([
            { orgId: KILIMA, request: { installationId: AMANI_INSTALLATION, grant } },
        ]);
        expect(intents.stored).toBeUndefined();
    });

    it("says when the organization already had the installation", async () => {
        const { useCases } = setUp();
        await useCases.startInstall({ orgId: KILIMA, returnTo: GITHUB_PAGE });

        expect(
            await useCases.finishGitHubRedirect({
                ...freshInstall,
                installation_id: String(KILIMA_LABS_INSTALLATION),
            }),
        ).toMatchObject({ kind: "installed", alreadyLinked: true });
    });

    it("links an install that came back without authorization using the session there is", async () => {
        const { installations, useCases } = setUp();
        await useCases.startInstall({ orgId: KILIMA, returnTo: GITHUB_PAGE });

        expect(await useCases.finishGitHubRedirect(installWithoutAuthorization)).toMatchObject({ kind: "installed" });
        expect(installations.links[0]?.request).toEqual({ installationId: AMANI_INSTALLATION });
    });

    it("authorizes first when there is no session, then links that installation on the way back", async () => {
        const { sessions, installations, intents, useCases } = setUp();
        await useCases.startInstall({ orgId: KILIMA, returnTo: GITHUB_PAGE });
        installations.failNext = refusal(403, GITHUB_AUTHORIZATION_REQUIRED);

        const leaving = await useCases.finishGitHubRedirect(installWithoutAuthorization);

        expect(leaving).toEqual({
            kind: "authorizing",
            handoff: aHandoff("https://github.com/login/oauth/authorize?state=authorize-1"),
        });
        expect(intents.stored).toBeDefined();

        const back = await useCases.finishGitHubRedirect(authorization);

        expect(back).toEqual({
            kind: "installed",
            orgId: KILIMA,
            destination: `${GITHUB_PAGE}?github=installed`,
            alreadyLinked: false,
        });
        expect(sessions.completed).toEqual([grant]);
        expect(installations.links.at(-1)).toEqual({ orgId: KILIMA, request: { installationId: AMANI_INSTALLATION } });
    });

    it("never guesses an organization: no intent means starting again, with nothing sent", async () => {
        const { sessions, installations, useCases } = setUp();

        expect(await useCases.finishGitHubRedirect(freshInstall)).toEqual({ kind: "startAgain", backTo: "/orgs" });
        expect(await useCases.finishGitHubRedirect(authorization)).toEqual({
            kind: "startAgain",
            backTo: "/orgs",
        });
        expect(installations.links).toEqual([]);
        expect(sessions.completed).toEqual([]);
    });

    it("treats an intent older than fifteen minutes as none", async () => {
        const { clock, installations, useCases } = setUp();
        await useCases.startInstall({ orgId: KILIMA, returnTo: GITHUB_PAGE });
        clock.advance(RETURN_INTENT_TTL_MS + 1);

        expect(await useCases.finishGitHubRedirect(freshInstall)).toMatchObject({ kind: "startAgain" });
        expect(installations.links).toEqual([]);
    });

    it("won't link a fresh install from an intent that names no organization", async () => {
        const { installations, useCases } = setUp();
        await useCases.startAuthorization({ returnTo: GITHUB_PAGE });

        expect(await useCases.finishGitHubRedirect(freshInstall)).toMatchObject({ kind: "startAgain" });
        expect(installations.links).toEqual([]);
    });

    it.each<[string, CallbackQuery]>([
        ["installRequested", { setup_action: "request" }],
        ["cancelled", { error: "access_denied" }],
        ["invalid", { code: grant.code }],
    ])("calls nothing for %s, going back where the round trip started", async (kind, query) => {
        const { sessions, installations, intents, useCases } = setUp();
        await useCases.startInstall({ orgId: KILIMA, returnTo: GITHUB_PAGE });

        expect(await useCases.finishGitHubRedirect(query)).toEqual({ kind, backTo: GITHUB_PAGE });
        expect(installations.links).toEqual([]);
        expect(sessions.completed).toEqual([]);
        expect(intents.stored).toBeUndefined();
    });

    it("offers the same round trip again when the state is refused", async () => {
        const { sessions, installations, useCases } = setUp();
        await useCases.startInstall({ orgId: KILIMA, returnTo: GITHUB_PAGE });
        installations.failNext = refusal(400, INVALID_AUTHORIZATION_STATE);

        const outcome = await useCases.finishGitHubRedirect(freshInstall);

        expect(outcome).toMatchObject({
            kind: "stateRejected",
            backTo: GITHUB_PAGE,
            retry: { kind: "install", orgId: KILIMA, returnTo: GITHUB_PAGE },
        });
        if (outcome.kind !== "stateRejected") throw new Error("unreachable");
        await useCases.retryGitHubRedirect(outcome.retry);
        expect(installations.installStarts).toEqual([KILIMA, KILIMA]);
        expect(sessions.starts).toBe(0);
    });

    it("retries an authorization that was linking an installation as the same authorization", async () => {
        const { sessions, useCases } = setUp();
        await useCases.startAuthorization({
            returnTo: GITHUB_PAGE,
            pending: { orgId: KILIMA, installationId: AMANI_INSTALLATION },
        });
        sessions.failNext = refusal(400, INVALID_AUTHORIZATION_STATE);

        const outcome = await useCases.finishGitHubRedirect(authorization);
        if (outcome.kind !== "stateRejected") throw new Error(`Expected stateRejected, got ${outcome.kind}`);

        await useCases.retryGitHubRedirect(outcome.retry);
        expect(sessions.starts).toBe(2);
        await expect(useCases.finishGitHubRedirect(authorization)).resolves.toMatchObject({
            kind: "installed",
        });
    });

    it.each([
        ["a refusal", refusal(403, INSTALLATION_NOT_ACCESSIBLE)],
        ["an unreachable service", new NetworkError()],
    ])("reports %s with a way to try again", async (_, failure) => {
        const { installations, useCases } = setUp();
        await useCases.startInstall({ orgId: KILIMA, returnTo: GITHUB_PAGE });
        installations.failNext = failure;

        expect(await useCases.finishGitHubRedirect(freshInstall)).toMatchObject({
            kind: "failed",
            backTo: GITHUB_PAGE,
            error: failure,
            retry: { kind: "install" },
        });
    });

    it("lets an ended Pallet session through, so the person is sent to sign in", async () => {
        const { installations, useCases } = setUp();
        await useCases.startInstall({ orgId: KILIMA, returnTo: GITHUB_PAGE });
        installations.failNext = new SessionExpiredError();

        await expect(useCases.finishGitHubRedirect(freshInstall)).rejects.toBeInstanceOf(SessionExpiredError);
    });
});

describe("starting a round trip", () => {
    it("records no intent when the service won't start one", async () => {
        const { installations, intents, useCases } = setUp();
        installations.failNext = refusal(403, "INSUFFICIENT_ROLE");

        await expect(useCases.startInstall({ orgId: KILIMA, returnTo: GITHUB_PAGE })).rejects.toThrow();
        expect(intents.stored).toBeUndefined();
    });
});
