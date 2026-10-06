import { asAppId, type AppId, type OrgId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import type { App, NewApp } from "../../domain/app";
import type { AppListQuery, AppTeamFilter } from "../../domain/app-list-query";
import { anApp } from "../../domain/testing/fixtures";
import type { AppChange } from "../../domain/update-app";
import type { AppRepository } from "../ports";

function pageOf<T>(all: readonly T[], { page, size }: { page: number; size: number }): Page<T> {
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

function matchesTeam(app: App, team: AppTeamFilter): boolean {
    switch (team.kind) {
        case "any":
            return true;
        case "unassigned":
            return app.teamId === null;
        case "team":
            return app.teamId === team.teamId;
    }
}

function matchesSearch(app: App, q: string): boolean {
    const needle = q.toLowerCase();
    return app.name.toLowerCase().includes(needle) || app.slug.toLowerCase().includes(needle);
}

export class InMemoryAppRepository implements AppRepository {
    readonly created: { readonly orgId: OrgId; readonly app: NewApp }[] = [];
    readonly updates: { readonly orgId: OrgId; readonly appId: AppId; readonly change: AppChange }[] = [];
    readonly deletions: { readonly orgId: OrgId; readonly appId: AppId }[] = [];
    /** Thrown by the next call, then cleared. */
    failNext: Error | undefined;

    constructor(private apps: App[] = [anApp()]) {}

    list(_orgId: OrgId, query: AppListQuery): Promise<Page<App>> {
        return this.answer(() =>
            pageOf(
                this.apps.filter(
                    (app) =>
                        matchesTeam(app, query.team) &&
                        (query.cloudProvider === null || app.cloudProvider === query.cloudProvider) &&
                        matchesSearch(app, query.q),
                ),
                query,
            ),
        );
    }

    get(_orgId: OrgId, appId: AppId): Promise<App> {
        return this.answer(() => this.find(appId));
    }

    create(orgId: OrgId, app: NewApp): Promise<App> {
        return this.answer(() => {
            this.created.push({ orgId, app });
            const saved = anApp({
                id: asAppId(`app-${String(this.apps.length + 1)}`),
                name: app.name,
                slug: app.slug ?? app.name.toLowerCase(),
                cloudProvider: app.cloudProvider,
                region: app.region,
                teamId: app.teamId,
            });
            this.apps.push(saved);
            return saved;
        });
    }

    update(orgId: OrgId, appId: AppId, change: AppChange): Promise<App> {
        return this.answer(() => {
            this.updates.push({ orgId, appId, change });
            const updated = { ...this.find(appId), ...change };
            this.apps = this.apps.map((app) => (app.id === appId ? updated : app));
            return updated;
        });
    }

    delete(orgId: OrgId, appId: AppId): Promise<void> {
        return this.answer(() => {
            this.deletions.push({ orgId, appId });
            this.apps = this.apps.filter((app) => app.id !== appId);
        });
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

    private find(appId: AppId): App {
        const app = this.apps.find((candidate) => candidate.id === appId);
        if (app === undefined) throw new Error(`No app ${appId}`);
        return app;
    }
}
