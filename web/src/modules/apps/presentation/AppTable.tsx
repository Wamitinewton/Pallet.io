"use client";

import type { OrgId } from "@/shared/domain/ids";
import { Badge, Icon, RelativeTime, Skeleton, Table, TableWrap, Td, Th } from "@/shared/presentation/ui";
import Link from "next/link";
import type { AriaAttributes } from "react";
import type { App } from "../domain/app";
import type { AppSort, AppSortField } from "../domain/app-list-query";
import { COLUMN_LABEL, NO_REPOSITORY_COPY } from "./app-copy";
import { appPath } from "./app-paths";
import styles from "./Apps.module.css";
import { AppTeam, type TeamDirectory } from "./AppTeam";
import { RunsOn } from "./RunsOn";

export interface AppTableProps {
    readonly orgId: OrgId;
    readonly apps: readonly App[];
    readonly teams: TeamDirectory | undefined;
    readonly sort: AppSort;
    /** True while an earlier page stays on screen as the next one loads. */
    readonly busy?: boolean;
}

export function AppTable({ orgId, apps, teams, sort, busy = false }: AppTableProps) {
    return (
        <TableWrap>
            <Table aria-label={COLUMN_LABEL.app} aria-busy={busy} className={busy ? styles.stale : undefined}>
                <AppTableHead sort={sort} />
                <tbody>
                    {apps.map((app) => (
                        <tr key={app.id}>
                            <Td>
                                <Link href={appPath(orgId, app.id)} className={styles.appName}>
                                    <span className={styles.appIcon} aria-hidden="true">
                                        <Icon name="apps" />
                                    </span>
                                    <span className={styles.nameBlock}>
                                        <span className={styles.name}>{app.name}</span>
                                        <span className={styles.slug}>{app.slug}</span>
                                    </span>
                                </Link>
                            </Td>
                            <Td>
                                <Badge outline>{NO_REPOSITORY_COPY}</Badge>
                            </Td>
                            <Td hideOnMobile>
                                <RunsOn app={app} />
                            </Td>
                            <Td hideOnMobile>
                                <AppTeam orgId={orgId} teamId={app.teamId} teams={teams} />
                            </Td>
                            <Td hideOnMobile className={styles.updated}>
                                <RelativeTime dateTime={app.updatedAt} />
                            </Td>
                        </tr>
                    ))}
                </tbody>
            </Table>
        </TableWrap>
    );
}

function ariaSort(field: AppSortField, sort: AppSort): AriaAttributes["aria-sort"] {
    if (sort.field !== field) return undefined;
    return sort.direction === "asc" ? "ascending" : "descending";
}

function AppTableHead({ sort }: Readonly<{ sort: AppSort }>) {
    return (
        <thead>
            <tr>
                <Th aria-sort={ariaSort("name", sort)}>{COLUMN_LABEL.app}</Th>
                <Th>{COLUMN_LABEL.repository}</Th>
                <Th hideOnMobile>{COLUMN_LABEL.runsOn}</Th>
                <Th hideOnMobile>{COLUMN_LABEL.team}</Th>
                <Th hideOnMobile>{COLUMN_LABEL.updated}</Th>
            </tr>
        </thead>
    );
}

export function AppTableSkeleton({ rows, sort, label }: Readonly<{ rows: number; sort: AppSort; label: string }>) {
    return (
        <TableWrap>
            <Table aria-label={label} aria-busy="true">
                <AppTableHead sort={sort} />
                <tbody>
                    {Array.from({ length: rows }, (_, index) => (
                        <tr key={index}>
                            <Td>
                                <div className={styles.skeletonApp}>
                                    <Skeleton width={34} height={34} />
                                    <div className={styles.skeletonText}>
                                        <Skeleton width="45%" height={14} />
                                        <Skeleton width="30%" height={12} />
                                    </div>
                                </div>
                            </Td>
                            <Td>
                                <Skeleton width={96} height={22} />
                            </Td>
                            <Td hideOnMobile>
                                <Skeleton width={110} height={12} />
                            </Td>
                            <Td hideOnMobile>
                                <Skeleton width={72} height={12} />
                            </Td>
                            <Td hideOnMobile>
                                <Skeleton width={64} height={12} />
                            </Td>
                        </tr>
                    ))}
                </tbody>
            </Table>
        </TableWrap>
    );
}
