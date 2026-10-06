import type { Clock } from "@/shared/domain/clock";
import { authorizeIntent, type GitHubHandoff, type PendingLink } from "../domain/return-intent";
import type { GitHubSessionRepository, ReturnIntentStore } from "./ports";

export interface StartAuthorizationCommand {
    readonly returnTo: string;
    /** An installation to link once the person is signed in to GitHub. */
    readonly pending?: PendingLink;
}

export type StartAuthorization = (command: StartAuthorizationCommand) => Promise<GitHubHandoff>;

export function makeStartAuthorization(
    sessions: GitHubSessionRepository,
    intents: ReturnIntentStore,
    clock: Clock,
): StartAuthorization {
    return async ({ returnTo, pending }) => {
        const handoff = await sessions.startAuthorization();
        intents.save(authorizeIntent(clock.now(), returnTo, pending));
        return handoff;
    };
}
