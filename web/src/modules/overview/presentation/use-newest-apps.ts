"use client";

import { NEWEST_APPS_QUERY, useAppList, type App } from "@/modules/apps";
import { useRepoLinks, type RepoLink } from "@/modules/repo-links";
import type { OrgId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import type { UseQueryResult } from "@tanstack/react-query";

export interface NewestApp {
    readonly app: App;
    /** Null data means the app has no link; each one loads, and fails, on its own. */
    readonly link: UseQueryResult<RepoLink | null>;
}

export interface NewestApps {
    readonly list: UseQueryResult<Page<App>>;
    readonly rows: readonly NewestApp[];
}

/** The five newest apps and their links: one list read, then at most five link reads, all shared by cache key. */
export function useNewestApps(orgId: OrgId): NewestApps {
    const list = useAppList(orgId, NEWEST_APPS_QUERY);
    const apps = list.data?.items ?? [];
    const links = useRepoLinks(
        orgId,
        apps.map(({ id }) => id),
    );
    const rows = apps.flatMap((app, index) => {
        const link = links[index];
        return link === undefined ? [] : [{ app, link }];
    });
    return { list, rows };
}
