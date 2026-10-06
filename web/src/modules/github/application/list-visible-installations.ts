import type { Page, PageRequest } from "@/shared/domain/page";
import type { VisibleInstallation } from "../domain/installation";
import type { GitHubSessionRepository } from "./ports";

export type ListVisibleInstallations = (page: PageRequest) => Promise<Page<VisibleInstallation>>;

export function makeListVisibleInstallations(sessions: GitHubSessionRepository): ListVisibleInstallations {
    return (page) => sessions.visibleInstallations(page);
}
