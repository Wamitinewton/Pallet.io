import type { GitHubHandoff, ReturnIntent } from "../domain/return-intent";
import { pendingLinkOf } from "../domain/return-intent";
import type { StartAuthorization } from "./start-authorization";
import type { StartInstall } from "./start-install";

/** Sets off on the same round trip again, with a new state, after one GitHub or the service refused. */
export type RetryGitHubRedirect = (intent: ReturnIntent) => Promise<GitHubHandoff>;

export function makeRetryGitHubRedirect(
    startAuthorization: StartAuthorization,
    startInstall: StartInstall,
): RetryGitHubRedirect {
    return (intent) => {
        if (intent.kind === "install") return startInstall({ orgId: intent.orgId, returnTo: intent.returnTo });
        const pending = pendingLinkOf(intent);
        return startAuthorization({ returnTo: intent.returnTo, ...(pending !== undefined && { pending }) });
    };
}
