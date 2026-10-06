import type { GitHubSessionRepository } from "./ports";

export type EndGitHubSession = () => Promise<void>;

export function makeEndGitHubSession(sessions: GitHubSessionRepository): EndGitHubSession {
    return () => sessions.end();
}
