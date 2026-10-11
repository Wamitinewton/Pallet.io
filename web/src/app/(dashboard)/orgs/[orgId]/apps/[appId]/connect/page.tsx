import { getServerUseCases, rethrowServerFailure } from "@/composition/server";
import { appIdFromParam, appQueries, isAppUnavailable } from "@/modules/apps";
import { githubQueries } from "@/modules/github";
import { organizationQueries, orgIdFromParam } from "@/modules/organizations";
import { ConnectRepoView, repoLinkQueries } from "@/modules/repo-links";
import { getServerQueryClient } from "@/shared/presentation/query/get-server-query-client";
import { dehydrate, HydrationBoundary } from "@tanstack/react-query";
import type { Metadata } from "next";
import { notFound } from "next/navigation";

export const metadata: Metadata = {
    title: "Link a repository",
};

/** A missing app is a 404; any other failure to read the page's subject goes to the error boundary. */
function settleAppFailure(error: unknown): Promise<never> {
    if (isAppUnavailable(error)) notFound();
    return rethrowServerFailure(error);
}

/** The link, the GitHub sign-in and the installations draw their own states; a failed prefetch costs a client fetch. */
const ignore = () => undefined;

export default async function ConnectRepositoryPage({ params }: PageProps<"/orgs/[orgId]/apps/[appId]/connect">) {
    const { orgId: orgParam, appId: appParam } = await params;
    const orgId = orgIdFromParam(orgParam);
    const appId = appIdFromParam(appParam);
    if (appId === undefined) notFound();

    const { organizations, apps, repoLinks, github } = getServerUseCases();
    const queryClient = getServerQueryClient();

    const organization = await queryClient
        .query(organizationQueries.detail(organizations.getOrganization, orgId))
        .catch(rethrowServerFailure);
    await Promise.all([
        queryClient.query(appQueries.detail(apps.getApp, orgId, appId)).catch(settleAppFailure),
        queryClient.query(repoLinkQueries.link(repoLinks.getRepoLink, orgId, appId)).catch(ignore),
        queryClient.query(githubQueries.session(github.getGitHubSession)).catch(ignore),
        queryClient.query(githubQueries.installations(github.listOrgInstallations, orgId)).catch(ignore),
    ]);

    return (
        <HydrationBoundary state={dehydrate(queryClient)}>
            <ConnectRepoView orgId={orgId} orgName={organization.name} appId={appId} />
        </HydrationBoundary>
    );
}
