"use client";

import { useOrgAccess } from "@/modules/members";
import { useTeamDirectory } from "@/modules/teams";
import type { OrgId } from "@/shared/domain/ids";
import { ErrorState } from "@/shared/presentation/errors";
import { PageBreadcrumbs } from "@/shared/presentation/shell";
import { Button, EmptyState, Icon, Page, PageHead, Pagination, Panel, PanelFoot } from "@/shared/presentation/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useMemo, type ReactNode } from "react";
import { appListQuery, CLEARED_APP_FILTERS, isFiltered } from "../domain/app-list-query";
import {
    appCountCopy,
    APPS_DESCRIPTION_COPY,
    APPS_TITLE_COPY,
    CLEAR_FILTERS_COPY,
    FIRST_PAGE_COPY,
    LOADING_APPS_COPY,
    NEW_APP_COPY,
    NO_APPS_COPY,
    NO_APPS_CREATOR_COPY,
    NO_APPS_TITLE_COPY,
    NO_MATCHES_COPY,
    NO_MATCHES_TITLE_COPY,
    PAST_LAST_PAGE_TITLE_COPY,
    WHO_CAN_CREATE_COPY,
} from "./app-copy";
import { orgOverviewPath } from "./app-paths";
import { useAppUseCases } from "./app-use-cases";
import { AppFilters } from "./AppFilters";
import styles from "./Apps.module.css";
import { AppTable, AppTableSkeleton } from "./AppTable";
import { byTeamId } from "./AppTeam";
import { CreateAppDialog } from "./CreateAppDialog";
import { appQueries } from "./queries";
import { useAppListParams } from "./use-app-params";

const LOADING_ROWS = 4;

export interface AppsViewProps {
    readonly orgId: OrgId;
    readonly orgName: string;
}

export function AppsView({ orgId, orgName }: AppsViewProps) {
    const access = useOrgAccess();
    const { listApps } = useAppUseCases();
    const { params, changeFilters, goToPage } = useAppListParams();
    const query = appListQuery(params);
    const apps = useQuery({ ...appQueries.list(listApps, orgId, query), placeholderData: keepPreviousData });
    const teams = useTeamDirectory(orgId).data;
    const teamsById = useMemo(() => (teams === undefined ? undefined : byTeamId(teams)), [teams]);
    const canCreate = access.can("apps.create");
    const clearFilters = () => {
        changeFilters(CLEARED_APP_FILTERS);
    };

    const createApp = (variant: "primary" | "secondary") =>
        canCreate && (
            <CreateAppDialog
                orgId={orgId}
                orgName={orgName}
                trigger={
                    <Button variant={variant}>
                        <Icon name="plus" />
                        {NEW_APP_COPY}
                    </Button>
                }
            />
        );

    const body = (): ReactNode => {
        if (apps.isError && apps.data === undefined) {
            return (
                <ErrorState
                    error={apps.error}
                    headingLevel={2}
                    retrying={apps.isFetching}
                    onRetry={() => void apps.refetch()}
                />
            );
        }
        if (apps.data === undefined) {
            return <AppTableSkeleton rows={LOADING_ROWS} sort={query.sort} label={LOADING_APPS_COPY} />;
        }
        if (apps.data.items.length === 0 && apps.data.totalItems > 0) {
            return (
                <EmptyState
                    icon="apps"
                    title={PAST_LAST_PAGE_TITLE_COPY}
                    actions={
                        <Button
                            variant="secondary"
                            size="sm"
                            onClick={() => {
                                goToPage(1);
                            }}
                        >
                            {FIRST_PAGE_COPY}
                        </Button>
                    }
                />
            );
        }
        if (apps.data.items.length === 0 && isFiltered(query)) {
            return (
                <EmptyState
                    icon="search"
                    title={NO_MATCHES_TITLE_COPY}
                    description={NO_MATCHES_COPY}
                    actions={
                        <Button variant="secondary" size="sm" onClick={clearFilters}>
                            {CLEAR_FILTERS_COPY}
                        </Button>
                    }
                />
            );
        }
        if (apps.data.items.length === 0) {
            return (
                <EmptyState
                    icon="apps"
                    title={NO_APPS_TITLE_COPY}
                    description={canCreate ? NO_APPS_CREATOR_COPY : NO_APPS_COPY}
                    actions={createApp("secondary")}
                />
            );
        }
        return (
            <AppTable
                orgId={orgId}
                apps={apps.data.items}
                teams={teamsById}
                sort={query.sort}
                busy={apps.isPlaceholderData}
            />
        );
    };

    const firstApp = apps.data === undefined || (apps.data.totalItems === 0 && !isFiltered(query));

    return (
        <Page>
            <PageBreadcrumbs items={[{ label: orgName, href: orgOverviewPath(orgId) }, { label: APPS_TITLE_COPY }]} />
            <PageHead
                title={APPS_TITLE_COPY}
                description={APPS_DESCRIPTION_COPY}
                actions={firstApp ? undefined : createApp("primary")}
            />

            <Panel className={styles.panel}>
                {!firstApp && <AppFilters params={params} teams={teams} onChange={changeFilters} />}
                {body()}
                {apps.data !== undefined && apps.data.totalItems > 0 && (
                    <PanelFoot>
                        <span className={styles.count}>{appCountCopy(apps.data.totalItems)}</span>
                        <span>{WHO_CAN_CREATE_COPY}</span>
                    </PanelFoot>
                )}
            </Panel>

            {apps.data !== undefined && (
                <Pagination
                    aria-label="Apps pages"
                    className={styles.pagination}
                    page={apps.data.page + 1}
                    totalPages={apps.data.totalPages}
                    disabled={apps.isPlaceholderData}
                    onPageChange={goToPage}
                />
            )}
        </Page>
    );
}
