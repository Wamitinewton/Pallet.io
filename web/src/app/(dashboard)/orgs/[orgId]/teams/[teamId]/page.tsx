import { getServerUseCases, rethrowServerFailure } from "@/composition/server";
import { organizationQueries, orgIdFromParam } from "@/modules/organizations";
import {
    isTeamUnavailable,
    loadTeamMemberSearchParams,
    teamIdFromParam,
    teamMemberListQuery,
    teamQueries,
    TeamView,
} from "@/modules/teams";
import { getServerQueryClient } from "@/shared/presentation/query/get-server-query-client";
import { dehydrate, HydrationBoundary } from "@tanstack/react-query";
import type { Metadata } from "next";
import { notFound } from "next/navigation";

type TeamPageProps = PageProps<"/orgs/[orgId]/teams/[teamId]">;

/** A missing team is a 404; any other failure to read the page's subject goes to the error boundary. */
function settleTeamFailure(error: unknown): Promise<never> {
    if (isTeamUnavailable(error)) notFound();
    return rethrowServerFailure(error);
}

/** The people list draws its own error state, so a failed prefetch only costs a client fetch. */
const ignore = () => undefined;

export async function generateMetadata({ params }: TeamPageProps): Promise<Metadata> {
    const { orgId, teamId } = await params;
    const id = teamIdFromParam(teamId);
    if (id === undefined) return { title: "Team" };
    const team = await getServerQueryClient()
        .query(teamQueries.detail(getServerUseCases().teams.getTeam, orgIdFromParam(orgId), id))
        .catch(ignore);
    return { title: team?.name ?? "Team" };
}

export default async function TeamPage({ params, searchParams }: TeamPageProps) {
    const { orgId: orgParam, teamId: teamParam } = await params;
    const orgId = orgIdFromParam(orgParam);
    const teamId = teamIdFromParam(teamParam);
    if (teamId === undefined) notFound();

    const { organizations, teams } = getServerUseCases();
    const queryClient = getServerQueryClient();

    const [organization, memberParams] = await Promise.all([
        queryClient.query(organizationQueries.detail(organizations.getOrganization, orgId)),
        loadTeamMemberSearchParams(searchParams),
    ]).catch(rethrowServerFailure);
    await Promise.all([
        queryClient.query(teamQueries.detail(teams.getTeam, orgId, teamId)).catch(settleTeamFailure),
        queryClient
            .query(teamQueries.members(teams.listTeamMembers, orgId, teamId, teamMemberListQuery(memberParams.page)))
            .catch(ignore),
    ]);

    return (
        <HydrationBoundary state={dehydrate(queryClient)}>
            <TeamView orgId={orgId} orgName={organization.name} teamId={teamId} />
        </HydrationBoundary>
    );
}
