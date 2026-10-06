import { getServerUseCases, rethrowServerFailure } from "@/composition/server";
import { organizationQueries, orgIdFromParam } from "@/modules/organizations";
import { loadTeamSearchParams, teamListQuery, teamQueries, TeamsView } from "@/modules/teams";
import { getServerQueryClient } from "@/shared/presentation/query/get-server-query-client";
import { dehydrate, HydrationBoundary } from "@tanstack/react-query";
import type { Metadata } from "next";

export const metadata: Metadata = {
    title: "Teams",
};

/** The grid draws its own error state, so a failed prefetch only costs a client fetch. */
const ignore = () => undefined;

export default async function TeamsPage({ params, searchParams }: PageProps<"/orgs/[orgId]/teams">) {
    const orgId = orgIdFromParam((await params).orgId);
    const { organizations, teams } = getServerUseCases();
    const queryClient = getServerQueryClient();

    const [organization, listParams] = await Promise.all([
        queryClient.query(organizationQueries.detail(organizations.getOrganization, orgId)),
        loadTeamSearchParams(searchParams),
    ]).catch(rethrowServerFailure);
    await queryClient.query(teamQueries.list(teams.listTeams, orgId, teamListQuery(listParams))).catch(ignore);

    return (
        <HydrationBoundary state={dehydrate(queryClient)}>
            <TeamsView orgId={orgId} orgName={organization.name} />
        </HydrationBoundary>
    );
}
