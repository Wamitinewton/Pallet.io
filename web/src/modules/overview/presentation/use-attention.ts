"use client";

import { useOrgInstallations } from "@/modules/github";
import { usePendingInviteCount } from "@/modules/invites";
import { useOrgAccess } from "@/modules/members";
import { useUnreadCount } from "@/modules/notifications";
import { linkHealth, type LiveDisconnectReason, type RepoLink } from "@/modules/repo-links";
import type { OrgId } from "@/shared/domain/ids";
import type { UseQueryResult } from "@tanstack/react-query";
import { buildAttentionList, type RepositoryState } from "../application/build-attention-list";
import type { OverviewAttentionItem } from "./attention-view";
import { useNewestApps } from "./use-newest-apps";

export interface Attention {
    readonly items: readonly OverviewAttentionItem[];
    /** True until every read the list depends on has answered, so neither the calm state nor a gap shows early. */
    readonly checking: boolean;
    /** The first error among the reads that failed, which leave the list incomplete. */
    readonly failure: unknown;
    readonly failed: boolean;
    readonly retry: () => void;
    readonly retrying: boolean;
}

function repositoryState(link: UseQueryResult<RepoLink | null>): RepositoryState<LiveDisconnectReason> {
    if (link.data === undefined) return { kind: "unknown" };
    const health = linkHealth(link.data);
    switch (health.kind) {
        case "unlinked":
            return { kind: "unlinked" };
        case "connected":
            return { kind: "connected", repoFullName: health.link.repoFullName, archived: false };
        case "connected-with-warnings":
            return {
                kind: "connected",
                repoFullName: health.link.repoFullName,
                archived: health.warnings.includes("REPOSITORY_ARCHIVED"),
            };
        case "disconnected":
            return { kind: "disconnected", repoFullName: health.link.repoFullName, reason: health.reason };
    }
}

/**
 * Built only from reads the overview makes anyway, or the shell has cached; a read the caller's role can't
 * make is never sent, and one that fails leaves its items out rather than the whole list.
 */
export function useAttention(orgId: OrgId): Attention {
    const access = useOrgAccess();
    const { list, rows } = useNewestApps(orgId);
    const canViewInstallations = access.can("github.installations.view");
    const installations = useOrgInstallations(orgId, { enabled: canViewInstallations });
    const pendingInvites = usePendingInviteCount(orgId);
    const unreadNotifications = useUnreadCount();

    const sources: readonly UseQueryResult[] = [
        list,
        ...rows.map(({ link }) => link),
        ...(canViewInstallations ? [installations] : []),
        ...(pendingInvites === undefined ? [] : [pendingInvites]),
    ];
    const failedSources = sources.filter((source) => source.isError && source.data === undefined);

    const items = buildAttentionList<LiveDisconnectReason>({
        apps:
            list.data === undefined
                ? undefined
                : rows.map(({ app, link }) => ({
                      app: { appId: app.id, appName: app.name },
                      repository: repositoryState(link),
                  })),
        installations: installations.data?.items.map(({ accountLogin, status }) => ({
            accountLogin,
            suspended: status === "SUSPENDED",
        })),
        canManageInstallations: access.can("github.installations.manage"),
        pendingInvites: pendingInvites?.data,
        unreadNotifications,
    });

    return {
        items,
        checking: sources.some((source) => source.isPending),
        failure: failedSources[0]?.error,
        failed: failedSources.length > 0,
        retry: () => {
            for (const source of failedSources) void source.refetch();
        },
        retrying: failedSources.some((source) => source.isFetching),
    };
}
