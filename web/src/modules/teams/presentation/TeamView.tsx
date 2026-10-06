"use client";

import { useOrgAccess } from "@/modules/members";
import type { OrgId, TeamId } from "@/shared/domain/ids";
import { ErrorState } from "@/shared/presentation/errors";
import { PageBreadcrumbs } from "@/shared/presentation/shell";
import { Button, Icon, Page, PageHead, SectionGap, Skeleton } from "@/shared/presentation/ui";
import { useQuery } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { isTeamUnavailable, type Team } from "../domain/team";
import { AddTeamMemberDialog } from "./AddTeamMemberDialog";
import { teamQueries } from "./queries";
import { ADD_PEOPLE_COPY, createdCopy, peopleCountCopy, TEAMS_TITLE_COPY } from "./team-copy";
import { orgOverviewPath, teamsPath } from "./team-paths";
import { useTeamUseCases } from "./team-use-cases";
import { TeamMembersPanel } from "./TeamMembersPanel";
import styles from "./Teams.module.css";
import { TeamSettingsPanel } from "./TeamSettingsPanel";
import { TeamUnavailable } from "./TeamUnavailable";

export interface TeamViewProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    readonly teamId: TeamId;
    /** The team's apps, which depend on this module and so are handed in by the page rather than imported. */
    readonly apps?: ReactNode;
}

export function TeamView({ orgId, orgName, teamId, apps }: TeamViewProps) {
    const { getTeam } = useTeamUseCases();
    const team = useQuery(teamQueries.detail(getTeam, orgId, teamId));

    if (team.data !== undefined) return <TeamPage orgId={orgId} orgName={orgName} team={team.data} apps={apps} />;
    if (isTeamUnavailable(team.error)) {
        return <TeamUnavailable orgId={orgId} />;
    }

    return (
        <Page>
            <PageBreadcrumbs
                items={[
                    { label: orgName, href: orgOverviewPath(orgId) },
                    { label: TEAMS_TITLE_COPY, href: teamsPath(orgId) },
                ]}
            />
            {team.isError ? (
                <ErrorState
                    error={team.error}
                    headingLevel={2}
                    retrying={team.isFetching}
                    onRetry={() => void team.refetch()}
                />
            ) : (
                <div role="status" aria-busy="true" aria-label="Loading team">
                    <Skeleton width="30%" height={28} />
                </div>
            )}
        </Page>
    );
}

interface TeamPageProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    readonly team: Team;
    readonly apps: ReactNode;
}

function TeamPage({ orgId, orgName, team, apps }: TeamPageProps) {
    const access = useOrgAccess();
    const [adding, setAdding] = useState(false);
    const canManage = access.can("teams.manage");
    const openAdd = () => {
        setAdding(true);
    };
    const aside = apps !== undefined || canManage;

    return (
        <Page>
            <PageBreadcrumbs
                items={[
                    { label: orgName, href: orgOverviewPath(orgId) },
                    { label: TEAMS_TITLE_COPY, href: teamsPath(orgId) },
                    { label: team.name },
                ]}
            />
            <PageHead
                title={team.name}
                description={
                    <>
                        <span className={styles.headSlug}>{team.slug}</span> · {createdCopy(team.createdAt)} ·{" "}
                        {peopleCountCopy(team.memberCount)}
                    </>
                }
                actions={
                    canManage && (
                        <Button variant="primary" onClick={openAdd}>
                            <Icon name="plus" />
                            {ADD_PEOPLE_COPY}
                        </Button>
                    )
                }
            />

            <div className={styles.layout} data-aside={aside || undefined}>
                <TeamMembersPanel
                    orgId={orgId}
                    orgName={orgName}
                    team={team}
                    onAddPeople={canManage ? openAdd : undefined}
                />
                {aside && (
                    <SectionGap>
                        {apps}
                        {canManage && <TeamSettingsPanel orgId={orgId} orgName={orgName} team={team} />}
                    </SectionGap>
                )}
            </div>

            {canManage && (
                <AddTeamMemberDialog
                    orgId={orgId}
                    orgName={orgName}
                    team={team}
                    open={adding}
                    onOpenChange={setAdding}
                />
            )}
        </Page>
    );
}
