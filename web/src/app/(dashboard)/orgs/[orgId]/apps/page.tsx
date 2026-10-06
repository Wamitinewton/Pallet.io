import { getServerUseCases, rethrowServerFailure } from "@/composition/server";
import { appListQuery, appQueries, AppsView, loadAppSearchParams } from "@/modules/apps";
import { organizationQueries, orgIdFromParam } from "@/modules/organizations";
import { teamQueries } from "@/modules/teams";
import { getServerQueryClient } from "@/shared/presentation/query/get-server-query-client";
import { dehydrate, HydrationBoundary } from "@tanstack/react-query";
import type { Metadata } from "next";

export const metadata: Metadata = {
    title: "Apps",
};

/** The table and the team names draw their own states, so a failed prefetch only costs a client fetch. */
const ignore = () => undefined;

export default async function AppsPage({ params, searchParams }: PageProps<"/orgs/[orgId]/apps">) {
    const orgId = orgIdFromParam((await params).orgId);
    const { organizations, apps, teams } = getServerUseCases();
    const queryClient = getServerQueryClient();

    const [organization, listParams] = await Promise.all([
        queryClient.query(organizationQueries.detail(organizations.getOrganization, orgId)),
        loadAppSearchParams(searchParams),
    ]).catch(rethrowServerFailure);
    await Promise.all([
        queryClient.query(appQueries.list(apps.listApps, orgId, appListQuery(listParams))),
        queryClient.query(teamQueries.directory(teams.listTeams, orgId)),
    ]).catch(ignore);

    return (
        <HydrationBoundary state={dehydrate(queryClient)}>
            <AppsView orgId={orgId} orgName={organization.name} />
        </HydrationBoundary>
    );
}
