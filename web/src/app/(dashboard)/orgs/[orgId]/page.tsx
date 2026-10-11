import { getServerUseCases, rethrowServerFailure } from "@/composition/server";
import { appQueries, NEWEST_APPS_QUERY } from "@/modules/apps";
import { githubQueries } from "@/modules/github";
import { organizationQueries, orgIdFromParam } from "@/modules/organizations";
import { OverviewView } from "@/modules/overview";
import { getServerQueryClient } from "@/shared/presentation/query/get-server-query-client";
import { dehydrate, HydrationBoundary } from "@tanstack/react-query";
import type { Metadata } from "next";

export const metadata: Metadata = {
    title: "Overview",
};

/**
 * Each panel draws its own states, so a failed prefetch only costs a client fetch. Repository links stream in
 * on the client, so the server doesn't spend five requests before first paint.
 */
const ignore = () => undefined;

export default async function OverviewPage({ params }: PageProps<"/orgs/[orgId]">) {
    const orgId = orgIdFromParam((await params).orgId);
    const { organizations, apps, github } = getServerUseCases();
    const queryClient = getServerQueryClient();

    const organization = await queryClient
        .query(organizationQueries.detail(organizations.getOrganization, orgId))
        .catch(rethrowServerFailure);
    await Promise.all([
        queryClient.query(appQueries.list(apps.listApps, orgId, NEWEST_APPS_QUERY)),
        queryClient.query(githubQueries.installations(github.listOrgInstallations, orgId)),
    ]).catch(ignore);

    return (
        <HydrationBoundary state={dehydrate(queryClient)}>
            <OverviewView orgId={orgId} orgName={organization.name} />
        </HydrationBoundary>
    );
}
