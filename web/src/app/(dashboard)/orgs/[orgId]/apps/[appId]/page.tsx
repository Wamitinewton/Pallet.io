import { getServerUseCases, rethrowServerFailure } from "@/composition/server";
import { appIdFromParam, appQueries, AppView, isAppUnavailable } from "@/modules/apps";
import { organizationQueries, orgIdFromParam } from "@/modules/organizations";
import { teamQueries } from "@/modules/teams";
import { getServerQueryClient } from "@/shared/presentation/query/get-server-query-client";
import { dehydrate, HydrationBoundary } from "@tanstack/react-query";
import type { Metadata } from "next";
import { notFound } from "next/navigation";

type AppPageProps = PageProps<"/orgs/[orgId]/apps/[appId]">;

/** A missing app is a 404; any other failure to read the page's subject goes to the error boundary. */
function settleAppFailure(error: unknown): Promise<never> {
    if (isAppUnavailable(error)) notFound();
    return rethrowServerFailure(error);
}

/** The team names draw their own state, so a failed prefetch only costs a client fetch. */
const ignore = () => undefined;

export async function generateMetadata({ params }: AppPageProps): Promise<Metadata> {
    const { orgId, appId } = await params;
    const id = appIdFromParam(appId);
    if (id === undefined) return { title: "App" };
    const app = await getServerQueryClient()
        .query(appQueries.detail(getServerUseCases().apps.getApp, orgIdFromParam(orgId), id))
        .catch(ignore);
    return { title: app?.name ?? "App" };
}

export default async function AppPage({ params }: AppPageProps) {
    const { orgId: orgParam, appId: appParam } = await params;
    const orgId = orgIdFromParam(orgParam);
    const appId = appIdFromParam(appParam);
    if (appId === undefined) notFound();

    const { organizations, apps, teams } = getServerUseCases();
    const queryClient = getServerQueryClient();

    const organization = await queryClient
        .query(organizationQueries.detail(organizations.getOrganization, orgId))
        .catch(rethrowServerFailure);
    await Promise.all([
        queryClient.query(appQueries.detail(apps.getApp, orgId, appId)).catch(settleAppFailure),
        queryClient.query(teamQueries.directory(teams.listTeams, orgId)).catch(ignore),
    ]);

    return (
        <HydrationBoundary state={dehydrate(queryClient)}>
            <AppView orgId={orgId} orgName={organization.name} appId={appId} />
        </HydrationBoundary>
    );
}
