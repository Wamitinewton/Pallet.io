import type { InstallationId, OrgId } from "@/shared/domain/ids";
import type { Page, PageRequest } from "@/shared/domain/page";
import type { GitHubSession } from "../domain/github-session";
import type {
    AuthorizationGrant,
    InstallationLink,
    LinkOutcome,
    LinkRequest,
    VisibleInstallation,
} from "../domain/installation";
import type { GitHubHandoff, ReturnIntent } from "../domain/return-intent";

/** The caller's own GitHub sign-in, which no organization owns. */
export interface GitHubSessionRepository {
    startAuthorization(): Promise<GitHubHandoff>;
    completeAuthorization(grant: AuthorizationGrant): Promise<GitHubSession>;
    /** Null when there is none, or it expired. */
    find(): Promise<GitHubSession | null>;
    /** Succeeds when there was nothing to end. */
    end(): Promise<void>;
    visibleInstallations(page: PageRequest): Promise<Page<VisibleInstallation>>;
}

export interface InstallationRepository {
    startInstall(orgId: OrgId): Promise<GitHubHandoff>;
    link(orgId: OrgId, request: LinkRequest): Promise<LinkOutcome>;
    /** Newest link first. */
    list(orgId: OrgId, page: PageRequest): Promise<Page<InstallationLink>>;
    /** This organization only; the app stays installed on GitHub and other organizations keep theirs. */
    unlink(orgId: OrgId, installationId: InstallationId): Promise<void>;
}

/** One intent per browser tab, kept across the trip to GitHub and back. */
export interface ReturnIntentStore {
    save(intent: ReturnIntent): void;
    /** Whatever is stored, unchecked: the domain decides whether it is an intent. */
    read(): unknown;
    clear(): void;
}
