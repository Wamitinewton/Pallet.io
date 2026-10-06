import type { InstallationId, OrgId } from "@/shared/domain/ids";
import type { Page, PageRequest } from "@/shared/domain/page";
import type { GitHubSession } from "../../domain/github-session";
import type {
    AuthorizationGrant,
    InstallationLink,
    LinkOutcome,
    LinkRequest,
    VisibleInstallation,
} from "../../domain/installation";
import type { GitHubHandoff, ReturnIntent } from "../../domain/return-intent";
import { aGitHubSession, aHandoff, anInstallationLink } from "../../domain/testing/fixtures";
import type { GitHubSessionRepository, InstallationRepository, ReturnIntentStore } from "../ports";

function pageOf<T>(all: readonly T[], { page, size }: PageRequest): Page<T> {
    const totalPages = Math.ceil(all.length / size);
    return {
        items: all.slice(page * size, (page + 1) * size),
        page,
        size,
        totalItems: all.length,
        totalPages,
        isFirst: page === 0,
        isLast: page >= totalPages - 1,
    };
}

/** Holds a scripted failure for the next call, then answers normally again. */
class Scripted {
    /** Thrown by the next call, then cleared. */
    failNext: Error | undefined;

    protected answer<T>(work: () => T): Promise<T> {
        const failure = this.failNext;
        this.failNext = undefined;
        if (failure !== undefined) return Promise.reject(failure);
        return Promise.resolve(work());
    }
}

export class InMemoryGitHubSessionRepository extends Scripted implements GitHubSessionRepository {
    readonly completed: AuthorizationGrant[] = [];
    starts = 0;

    constructor(
        public session: GitHubSession | null = null,
        public visible: VisibleInstallation[] = [],
    ) {
        super();
    }

    startAuthorization(): Promise<GitHubHandoff> {
        return this.answer(() => {
            this.starts += 1;
            return aHandoff(`https://github.com/login/oauth/authorize?state=authorize-${String(this.starts)}`);
        });
    }

    completeAuthorization(grant: AuthorizationGrant): Promise<GitHubSession> {
        return this.answer(() => {
            this.completed.push(grant);
            this.session = aGitHubSession();
            return this.session;
        });
    }

    find(): Promise<GitHubSession | null> {
        return this.answer(() => this.session);
    }

    end(): Promise<void> {
        return this.answer(() => {
            this.session = null;
        });
    }

    visibleInstallations(page: PageRequest): Promise<Page<VisibleInstallation>> {
        return this.answer(() => pageOf(this.visible, page));
    }
}

export class InMemoryInstallationRepository extends Scripted implements InstallationRepository {
    readonly links: { readonly orgId: OrgId; readonly request: LinkRequest }[] = [];
    readonly unlinked: { readonly orgId: OrgId; readonly installationId: InstallationId }[] = [];
    readonly installStarts: OrgId[] = [];

    constructor(public linked: InstallationLink[] = []) {
        super();
    }

    startInstall(orgId: OrgId): Promise<GitHubHandoff> {
        return this.answer(() => {
            this.installStarts.push(orgId);
            return aHandoff(`https://github.com/apps/pallet/installations/new?state=install-${orgId}`);
        });
    }

    link(orgId: OrgId, request: LinkRequest): Promise<LinkOutcome> {
        return this.answer(() => {
            this.links.push({ orgId, request });
            const existing = this.linked.find((link) => link.installationId === request.installationId);
            if (existing !== undefined) return { link: existing, alreadyLinked: true };
            const link = anInstallationLink({ installationId: request.installationId });
            this.linked.push(link);
            return { link, alreadyLinked: false };
        });
    }

    list(_orgId: OrgId, page: PageRequest): Promise<Page<InstallationLink>> {
        return this.answer(() => pageOf(this.linked, page));
    }

    unlink(orgId: OrgId, installationId: InstallationId): Promise<void> {
        return this.answer(() => {
            this.unlinked.push({ orgId, installationId });
            this.linked = this.linked.filter((link) => link.installationId !== installationId);
        });
    }
}

/** Stores the intent as JSON, the way the browser would, so a read never hands back the saved object itself. */
export class InMemoryReturnIntentStore implements ReturnIntentStore {
    stored: string | undefined;

    save(intent: ReturnIntent): void {
        this.stored = JSON.stringify(intent);
    }

    read(): unknown {
        return this.stored === undefined ? undefined : JSON.parse(this.stored);
    }

    clear(): void {
        this.stored = undefined;
    }
}
