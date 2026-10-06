import type { GitHubSession } from "../domain/github-session";
import type { AuthorizationGrant } from "../domain/installation";
import type { GitHubSessionRepository } from "./ports";

export type CompleteAuthorization = (grant: AuthorizationGrant) => Promise<GitHubSession>;

export function makeCompleteAuthorization(sessions: GitHubSessionRepository): CompleteAuthorization {
    return (grant) => sessions.completeAuthorization(grant);
}
