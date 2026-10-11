import type { AppId, InstallationId, OrgId } from "@/shared/domain/ids";
import { asIsoInstant } from "@/shared/domain/instant";
import type { Page } from "@/shared/domain/page";
import type { BuildSettingsChange } from "../../domain/build-settings";
import type { ManualBuild, ManualBuildRequest } from "../../domain/manual-build";
import type { PickerRepository, RepositoryQuery } from "../../domain/picker-repository";
import type { RepoLink } from "../../domain/repo-link";
import { aRepoLink } from "../../domain/testing/fixtures";
import type { LinkRequest, RepoLinkRepository, RepositoryPickerRepository } from "../ports";

interface BuildCall {
    readonly orgId: OrgId;
    readonly appId: AppId;
    readonly request: ManualBuildRequest;
    readonly idempotencyKey: string;
}

/** One link per app, kept as the backend would; `failNext` is thrown by the next call, then cleared. */
export class InMemoryRepoLinkRepository implements RepoLinkRepository {
    readonly linked: { readonly orgId: OrgId; readonly appId: AppId; readonly request: LinkRequest }[] = [];
    readonly tunes: { readonly change: BuildSettingsChange; readonly version: number }[] = [];
    readonly builds: BuildCall[] = [];
    failNext: Error | undefined;
    private readonly links = new Map<AppId, RepoLink>();

    constructor(links: readonly RepoLink[] = [aRepoLink()]) {
        for (const link of links) this.links.set(link.appId, link);
    }

    /** Someone else's save landing first. */
    replace(link: RepoLink): void {
        this.links.set(link.appId, link);
    }

    find(_orgId: OrgId, appId: AppId): Promise<RepoLink | null> {
        return this.answer(() => this.links.get(appId) ?? null);
    }

    link(orgId: OrgId, appId: AppId, request: LinkRequest): Promise<RepoLink> {
        return this.answer(() => {
            this.linked.push({ orgId, appId, request });
            const link = aRepoLink({
                appId,
                installationId: request.installationId,
                repoId: request.repoId,
                ...request.settings,
                version: 0,
            });
            this.links.set(appId, link);
            return link;
        });
    }

    tune(_orgId: OrgId, appId: AppId, change: BuildSettingsChange, version: number): Promise<RepoLink> {
        return this.answer(() => {
            this.tunes.push({ change, version });
            const updated = { ...this.require(appId), ...change, version: version + 1 };
            this.links.set(appId, updated);
            return updated;
        });
    }

    takeOverVerification(_orgId: OrgId, appId: AppId): Promise<RepoLink> {
        return this.answer(() => this.require(appId));
    }

    disconnect(_orgId: OrgId, appId: AppId): Promise<void> {
        return this.answer(() => {
            this.links.set(appId, {
                ...this.require(appId),
                connection: {
                    status: "DISCONNECTED",
                    reason: "UNLINKED_BY_USER",
                    disconnectedAt: asIsoInstant("2026-01-15T12:00:00Z"),
                },
            });
        });
    }

    requestBuild(
        orgId: OrgId,
        appId: AppId,
        request: ManualBuildRequest,
        idempotencyKey: string,
    ): Promise<ManualBuild> {
        return this.answer(() => {
            this.builds.push({ orgId, appId, request, idempotencyKey });
            const link = this.require(appId);
            return {
                eventId: `event-${idempotencyKey}`,
                commitSha: link.lastAcceptedHead?.sha ?? "0123456789abcdef0123456789abcdef01234567",
                branch: request.branch ?? link.productionBranch,
            };
        });
    }

    private require(appId: AppId): RepoLink {
        const link = this.links.get(appId);
        if (link === undefined) throw new Error(`No link for ${appId}`);
        return link;
    }

    private answer<T>(work: () => T): Promise<T> {
        const failure = this.failNext;
        this.failNext = undefined;
        if (failure !== undefined) return Promise.reject(failure);
        try {
            return Promise.resolve(work());
        } catch (error) {
            return Promise.reject(error instanceof Error ? error : new Error(String(error)));
        }
    }
}

export class InMemoryRepositoryPicker implements RepositoryPickerRepository {
    readonly queries: { readonly installationId: InstallationId; readonly query: RepositoryQuery }[] = [];

    constructor(private readonly repositories: readonly PickerRepository[] = []) {}

    list(_orgId: OrgId, installationId: InstallationId, query: RepositoryQuery): Promise<Page<PickerRepository>> {
        this.queries.push({ installationId, query });
        const matching = this.repositories.filter((repository) =>
            repository.fullName.split("/")[1]?.startsWith(query.q),
        );
        const totalPages = Math.ceil(matching.length / query.size);
        return Promise.resolve({
            items: matching.slice(query.page * query.size, (query.page + 1) * query.size),
            page: query.page,
            size: query.size,
            totalItems: matching.length,
            totalPages,
            isFirst: query.page === 0,
            isLast: query.page >= totalPages - 1,
        });
    }
}
