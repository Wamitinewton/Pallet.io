import type { GitHubSession } from "../domain/github-session";
import type { GitHubSessionRepository } from "./ports";

export type GetGitHubSession = () => Promise<GitHubSession | null>;

export function makeGetGitHubSession(sessions: GitHubSessionRepository): GetGitHubSession {
    return () => sessions.find();
}
