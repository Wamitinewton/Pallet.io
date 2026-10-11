"use client";

import { appPath, appsPath, CreateAppDialog, RunsOn } from "@/modules/apps";
import { useOrgAccess } from "@/modules/members";
import { linkHealth, RepoStatusBadge, type RepoLink } from "@/modules/repo-links";
import type { OrgId } from "@/shared/domain/ids";
import { ErrorState } from "@/shared/presentation/errors";
import {
    Badge,
    Button,
    EmptyState,
    Icon,
    Panel,
    PanelHead,
    RelativeTime,
    Sha,
    Skeleton,
} from "@/shared/presentation/ui";
import type { UseQueryResult } from "@tanstack/react-query";
import Link from "next/link";
import {
    APPS_UNAVAILABLE_COPY,
    ARCHIVED_COPY,
    CHECKING_COPY,
    LINK_TO_BUILD_COPY,
    LOADING_APPS_COPY,
    LOST_ACCESS_COPY,
    NEW_APP_COPY,
    NEWEST_APPS_TITLE_COPY,
    NO_APPS_COPY,
    NO_APPS_CREATOR_COPY,
    NO_APPS_TITLE_COPY,
    NO_PUSH_YET_COPY,
    NO_REPOSITORY_COPY,
    ON_COPY,
    UNAVAILABLE_COPY,
    VIEW_ALL_APPS_LABEL_COPY,
    VIEW_ALL_COPY,
} from "./overview-copy";
import styles from "./Overview.module.css";
import { useNewestApps, type NewestApp, type NewestApps } from "./use-newest-apps";

const HEADING_ID = "overview-newest-apps";
const LOADING_ROWS = 3;

export interface RecentAppsProps {
    readonly orgId: OrgId;
    readonly orgName: string;
}

/** Newest rather than recently active: the backend sorts apps only by name and creation. */
export function RecentApps({ orgId, orgName }: RecentAppsProps) {
    const { list, rows } = useNewestApps(orgId);

    return (
        <Panel aria-labelledby={HEADING_ID}>
            <PanelHead>
                <h2 id={HEADING_ID}>{NEWEST_APPS_TITLE_COPY}</h2>
                {rows.length > 0 && (
                    <Button asChild variant="ghost" size="sm">
                        <Link href={appsPath(orgId)} aria-label={VIEW_ALL_APPS_LABEL_COPY}>
                            {VIEW_ALL_COPY}
                            <Icon name="chevron-right" />
                        </Link>
                    </Button>
                )}
            </PanelHead>
            <RecentAppsBody orgId={orgId} orgName={orgName} list={list} rows={rows} />
        </Panel>
    );
}

function RecentAppsBody({ orgId, orgName, list, rows }: RecentAppsProps & NewestApps) {
    const canCreate = useOrgAccess().can("apps.create");

    if (list.data === undefined) {
        return list.isError ? (
            <ErrorState
                error={list.error}
                title={APPS_UNAVAILABLE_COPY}
                headingLevel={3}
                retrying={list.isFetching}
                onRetry={() => void list.refetch()}
            />
        ) : (
            <div role="status" aria-busy="true" aria-label={LOADING_APPS_COPY}>
                {Array.from({ length: LOADING_ROWS }, (_, index) => (
                    <div key={index} className={styles.skeletonRow}>
                        <Skeleton width="40%" height={14} />
                        <Skeleton width="70%" height={12} />
                    </div>
                ))}
            </div>
        );
    }

    if (rows.length === 0) {
        return (
            <EmptyState
                icon="apps"
                title={NO_APPS_TITLE_COPY}
                headingLevel={3}
                description={canCreate ? NO_APPS_CREATOR_COPY : NO_APPS_COPY}
                actions={
                    canCreate && (
                        <CreateAppDialog
                            orgId={orgId}
                            orgName={orgName}
                            trigger={
                                <Button variant="secondary" size="sm">
                                    <Icon name="plus" />
                                    {NEW_APP_COPY}
                                </Button>
                            }
                        />
                    )
                }
            />
        );
    }

    return (
        <ul className={styles.rows}>
            {rows.map((row) => (
                <li key={row.app.id}>
                    <AppRow orgId={orgId} row={row} />
                </li>
            ))}
        </ul>
    );
}

function AppRow({ orgId, row: { app, link } }: Readonly<{ orgId: OrgId; row: NewestApp }>) {
    return (
        <Link href={appPath(orgId, app.id)} className={styles.row}>
            <span className={styles.name}>{app.name}</span>
            <LinkBadge link={link} />
            <span className={styles.meta}>
                <RunsOn app={app} />
                <RepositoryFacts link={link} />
            </span>
        </Link>
    );
}

function LinkBadge({ link }: Readonly<{ link: UseQueryResult<RepoLink | null> }>) {
    if (link.data === undefined) return <Badge outline>{link.isError ? UNAVAILABLE_COPY : CHECKING_COPY}</Badge>;
    const health = linkHealth(link.data);
    return health.kind === "unlinked" ? <Badge>{NO_REPOSITORY_COPY}</Badge> : <RepoStatusBadge health={health} />;
}

function RepositoryFacts({ link }: Readonly<{ link: UseQueryResult<RepoLink | null> }>) {
    if (link.data === undefined) return null;
    const health = linkHealth(link.data);

    switch (health.kind) {
        case "unlinked":
            return <span className={styles.fact}>{LINK_TO_BUILD_COPY}</span>;
        case "disconnected":
            return (
                <>
                    <Repository link={health.link} />
                    <span className={styles.fact}>
                        {LOST_ACCESS_COPY} <RelativeTime dateTime={health.disconnectedAt} />
                    </span>
                </>
            );
        case "connected":
        case "connected-with-warnings": {
            const head = health.link.lastAcceptedHead;
            return (
                <>
                    <Repository link={health.link} />
                    {health.kind === "connected-with-warnings" && health.warnings.includes("REPOSITORY_ARCHIVED") && (
                        <span className={styles.fact}>
                            <Icon name="archive" />
                            {ARCHIVED_COPY}
                        </span>
                    )}
                    <span className={styles.fact}>
                        <Icon name="commit" />
                        {head === null ? (
                            NO_PUSH_YET_COPY
                        ) : (
                            <>
                                <Sha value={head.sha} /> {ON_COPY} {head.branch},{" "}
                                <RelativeTime dateTime={head.advancedAt} />
                            </>
                        )}
                    </span>
                </>
            );
        }
    }
}

function Repository({ link }: Readonly<{ link: RepoLink }>) {
    return (
        <>
            <span className={styles.fact}>
                <Icon name="github" />
                {link.repoFullName}
            </span>
            {link.rootDirectory !== "" && (
                <span className={styles.fact}>
                    <Icon name="folder" />
                    {link.rootDirectory}
                </span>
            )}
        </>
    );
}
