"use client";

import { useOrgAccess } from "@/modules/members";
import type { OrgId } from "@/shared/domain/ids";
import { ErrorState } from "@/shared/presentation/errors";
import { PageBreadcrumbs } from "@/shared/presentation/shell";
import { Button, EmptyState, Icon, Page, PageHead, Pagination, Select } from "@/shared/presentation/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { formatTeamSort, parseTeamSort, teamListQuery } from "../domain/team-list-query";
import { NewTeamDialog } from "./NewTeamDialog";
import { teamQueries } from "./queries";
import {
    FIRST_PAGE_COPY,
    LOADING_TEAMS_COPY,
    NEW_TEAM_COPY,
    NO_TEAMS_ADMIN_COPY,
    NO_TEAMS_COPY,
    NO_TEAMS_TITLE_COPY,
    PAST_LAST_PAGE_TITLE_COPY,
    SORT_LABEL,
    TEAMS_DESCRIPTION_COPY,
    TEAMS_LIST_LABEL_COPY,
    TEAMS_TITLE_COPY,
} from "./team-copy";
import { orgOverviewPath } from "./team-paths";
import { useTeamUseCases } from "./team-use-cases";
import { TeamCard, TeamCardSkeleton } from "./TeamCard";
import styles from "./Teams.module.css";
import { useTeamListParams } from "./use-team-params";

const SORT_OPTIONS = Object.entries(SORT_LABEL);
const LOADING_CARDS = 3;

export interface TeamsViewProps {
    readonly orgId: OrgId;
    readonly orgName: string;
}

export function TeamsView({ orgId, orgName }: TeamsViewProps) {
    const access = useOrgAccess();
    const { listTeams } = useTeamUseCases();
    const { params, changeSort, goToPage } = useTeamListParams();
    const query = teamListQuery(params);
    const teams = useQuery({ ...teamQueries.list(listTeams, orgId, query), placeholderData: keepPreviousData });
    const canManage = access.can("teams.manage");

    const newTeam = (variant: "primary" | "secondary") =>
        canManage && (
            <NewTeamDialog
                orgId={orgId}
                trigger={
                    <Button variant={variant}>
                        <Icon name="plus" />
                        {NEW_TEAM_COPY}
                    </Button>
                }
            />
        );

    const body = (): ReactNode => {
        if (teams.isError && teams.data === undefined) {
            return (
                <ErrorState
                    error={teams.error}
                    headingLevel={2}
                    retrying={teams.isFetching}
                    onRetry={() => void teams.refetch()}
                />
            );
        }
        if (teams.data === undefined) {
            return (
                <ul className={styles.grid} aria-label={LOADING_TEAMS_COPY} aria-busy="true">
                    {Array.from({ length: LOADING_CARDS }, (_, index) => (
                        <li key={index}>
                            <TeamCardSkeleton />
                        </li>
                    ))}
                </ul>
            );
        }
        if (teams.data.items.length === 0 && teams.data.totalItems > 0) {
            return (
                <EmptyState
                    icon="teams"
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
        if (teams.data.items.length === 0) {
            return (
                <EmptyState
                    icon="teams"
                    title={NO_TEAMS_TITLE_COPY}
                    description={canManage ? NO_TEAMS_ADMIN_COPY : NO_TEAMS_COPY}
                    actions={newTeam("secondary")}
                />
            );
        }
        return (
            <>
                <div className={styles.toolbar}>
                    <Select
                        aria-label="Sort"
                        className={styles.sort}
                        value={formatTeamSort(query.sort)}
                        onChange={(event) => {
                            const sort = parseTeamSort(event.currentTarget.value);
                            if (sort !== undefined) changeSort(sort);
                        }}
                    >
                        {SORT_OPTIONS.map(([value, label]) => (
                            <option key={value} value={value}>
                                {label}
                            </option>
                        ))}
                    </Select>
                </div>
                <ul className={styles.grid} aria-label={TEAMS_LIST_LABEL_COPY} aria-busy={teams.isPlaceholderData}>
                    {teams.data.items.map((team) => (
                        <li key={team.id}>
                            <TeamCard orgId={orgId} team={team} />
                        </li>
                    ))}
                </ul>
                <Pagination
                    aria-label="Teams pages"
                    className={styles.pagination}
                    page={teams.data.page + 1}
                    totalPages={teams.data.totalPages}
                    disabled={teams.isPlaceholderData}
                    onPageChange={goToPage}
                />
            </>
        );
    };

    const hasTeams = teams.data !== undefined && teams.data.totalItems > 0;

    return (
        <Page>
            <PageBreadcrumbs items={[{ label: orgName, href: orgOverviewPath(orgId) }, { label: TEAMS_TITLE_COPY }]} />
            <PageHead
                title={TEAMS_TITLE_COPY}
                description={TEAMS_DESCRIPTION_COPY}
                actions={hasTeams ? newTeam("primary") : undefined}
            />
            {body()}
        </Page>
    );
}
