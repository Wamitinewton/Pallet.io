import type { Clock } from "@/shared/domain/clock";
import { ApiError, SessionExpiredError } from "@/shared/domain/errors";
import type { OrgId } from "@/shared/domain/ids";
import {
    classifyCallback,
    type CallbackQuery,
    type GitHubCallback,
    type GitHubRedirectOutcome,
} from "../domain/callback";
import { GITHUB_AUTHORIZATION_REQUIRED, INVALID_AUTHORIZATION_STATE } from "../domain/github-errors";
import type { LinkOutcome } from "../domain/installation";
import {
    backPath,
    pendingLinkOf,
    readReturnIntent,
    returnDestination,
    type ReturnIntent,
} from "../domain/return-intent";
import type { CompleteAuthorization } from "./complete-authorization";
import type { LinkInstallation } from "./link-installation";
import type { ReturnIntentStore } from "./ports";
import type { StartAuthorization } from "./start-authorization";

/** Takes GitHub's query parameters as they arrived, untrusted. */
export type FinishGitHubRedirect = (query: CallbackQuery) => Promise<GitHubRedirectOutcome>;

export interface FinishGitHubRedirectDependencies {
    readonly completeAuthorization: CompleteAuthorization;
    readonly linkInstallation: LinkInstallation;
    readonly startAuthorization: StartAuthorization;
    readonly intents: ReturnIntentStore;
    readonly clock: Clock;
}

function installed(intent: ReturnIntent, orgId: OrgId, { alreadyLinked }: LinkOutcome): GitHubRedirectOutcome {
    return { kind: "installed", orgId, destination: returnDestination(intent, "installed"), alreadyLinked };
}

function isRefusal(error: unknown, code: string): boolean {
    return error instanceof ApiError && error.is(code);
}

/**
 * Settles GitHub's redirect against the intent this tab recorded before leaving. The intent is single-use:
 * it is cleared on the way in, whatever happens next, and an install's organization only ever comes from it.
 */
export function makeFinishGitHubRedirect({
    completeAuthorization,
    linkInstallation,
    startAuthorization,
    intents,
    clock,
}: FinishGitHubRedirectDependencies): FinishGitHubRedirect {
    async function settle(callback: GitHubCallback, intent: ReturnIntent): Promise<GitHubRedirectOutcome | undefined> {
        switch (callback.kind) {
            case "authorization": {
                await completeAuthorization(callback.grant);
                const pending = pendingLinkOf(intent);
                if (pending === undefined) {
                    return { kind: "connected", destination: returnDestination(intent, "connected") };
                }
                const link = await linkInstallation(pending.orgId, { installationId: pending.installationId });
                return installed(intent, pending.orgId, link);
            }
            case "freshInstall": {
                const orgId = intent.orgId;
                if (orgId === undefined) return undefined;
                const request = { installationId: callback.installationId, grant: callback.grant };
                return installed(intent, orgId, await linkInstallation(orgId, request));
            }
            case "installWithoutAuthorization": {
                const orgId = intent.orgId;
                if (orgId === undefined) return undefined;
                const { installationId } = callback;
                try {
                    return installed(intent, orgId, await linkInstallation(orgId, { installationId }));
                } catch (error) {
                    if (!isRefusal(error, GITHUB_AUTHORIZATION_REQUIRED)) throw error;
                    const handoff = await startAuthorization({
                        returnTo: intent.returnTo,
                        pending: { orgId, installationId },
                    });
                    return { kind: "authorizing", handoff };
                }
            }
            case "installRequested":
            case "cancelled":
            case "invalid":
                return undefined;
        }
    }

    return async (query) => {
        const callback = classifyCallback(query);
        const intent = readReturnIntent(intents.read(), clock.now());
        intents.clear();
        const backTo = backPath(intent);

        if (callback.kind === "installRequested" || callback.kind === "cancelled" || callback.kind === "invalid") {
            return { kind: callback.kind, backTo };
        }
        if (intent === undefined) return { kind: "startAgain", backTo };

        try {
            return (await settle(callback, intent)) ?? { kind: "startAgain", backTo };
        } catch (error) {
            if (error instanceof SessionExpiredError) throw error;
            if (isRefusal(error, INVALID_AUTHORIZATION_STATE)) return { kind: "stateRejected", backTo, retry: intent };
            return { kind: "failed", backTo, retry: intent, error };
        }
    };
}
