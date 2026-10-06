"use client";

import type { OrgId, TeamId } from "@/shared/domain/ids";
import { ErrorState } from "@/shared/presentation/errors";
import { List, ListItem, Panel, PanelBody, PanelFoot, PanelHead, Skeleton, Text } from "@/shared/presentation/ui";
import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { teamAppsQuery } from "../domain/app-list-query";
import { LOADING_TEAM_APPS_COPY, NO_TEAM_APPS_COPY, seeAllTeamAppsCopy, TEAM_APPS_TITLE_COPY } from "./app-copy";
import { appPath, appsPath } from "./app-paths";
import { useAppUseCases } from "./app-use-cases";
import styles from "./Apps.module.css";
import { appQueries } from "./queries";
import { RunsOn } from "./RunsOn";

const LOADING_ROWS = 2;

export interface TeamAppsPanelProps {
    readonly orgId: OrgId;
    readonly teamId: TeamId;
}

/** The apps a team owns, read only: they are created and reassigned from the apps pages. */
export function TeamAppsPanel({ orgId, teamId }: TeamAppsPanelProps) {
    const { listApps } = useAppUseCases();
    const apps = useQuery(appQueries.list(listApps, orgId, teamAppsQuery(teamId)));

    const body = () => {
        if (apps.isError && apps.data === undefined) {
            return (
                <ErrorState
                    error={apps.error}
                    headingLevel={3}
                    retrying={apps.isFetching}
                    onRetry={() => void apps.refetch()}
                />
            );
        }
        if (apps.data === undefined) {
            return (
                <List aria-label={LOADING_TEAM_APPS_COPY} aria-busy="true">
                    {Array.from({ length: LOADING_ROWS }, (_, index) => (
                        <ListItem key={index}>
                            <Skeleton width="50%" height={14} />
                        </ListItem>
                    ))}
                </List>
            );
        }
        if (apps.data.items.length === 0) {
            return (
                <PanelBody>
                    <Text tone="muted" size="sm">
                        {NO_TEAM_APPS_COPY}
                    </Text>
                </PanelBody>
            );
        }
        return (
            <List aria-label={TEAM_APPS_TITLE_COPY} className={styles.teamApps}>
                {apps.data.items.map((app) => (
                    <ListItem key={app.id} className={styles.teamApp}>
                        <Link href={appPath(orgId, app.id)}>{app.name}</Link>
                        <Text tone="muted" size="sm">
                            <RunsOn app={app} />
                        </Text>
                    </ListItem>
                ))}
            </List>
        );
    };

    return (
        <Panel className={styles.panel} aria-label={TEAM_APPS_TITLE_COPY}>
            <PanelHead title={TEAM_APPS_TITLE_COPY} />
            {body()}
            {apps.data !== undefined && apps.data.totalItems > apps.data.items.length && (
                <PanelFoot>
                    <Link href={appsPath(orgId, { team: teamId })}>{seeAllTeamAppsCopy(apps.data.totalItems)}</Link>
                </PanelFoot>
            )}
        </Panel>
    );
}
