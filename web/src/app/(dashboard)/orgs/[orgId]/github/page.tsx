import { getServerUseCases, rethrowServerFailure } from "@/composition/server";
import { githubQueries, GitHubView } from "@/modules/github";
import { memberQueries } from "@/modules/members";
import { organizationQueries, orgIdFromParam } from "@/modules/organizations";
import { getServerQueryClient } from "@/shared/presentation/query/get-server-query-client";
import { dehydrate, HydrationBoundary } from "@tanstack/react-query";
import type { Metadata } from "next";

export const metadata: Metadata = {
    title: "GitHub",
};

/** The list and the session panel draw their own states, so a failed prefetch only costs a client fetch. */
const ignore = () => undefined;

export default async function GitHubPage({ params }: PageProps<"/orgs/[orgId]/github">) {
    const orgId = orgIdFromParam((await params).orgId);
    const { organizations, github, members } = getServerUseCases();
    const queryClient = getServerQueryClient();

    const organization = await queryClient
        .query(organizationQueries.detail(organizations.getOrganization, orgId))
        .catch(rethrowServerFailure);
    await Promise.all([
        queryClient.query(githubQueries.installations(github.listOrgInstallations, orgId)),
        queryClient.query(githubQueries.session(github.getGitHubSession)),
        queryClient.query(memberQueries.directory(members.listMembers, orgId)),
    ]).catch(ignore);

    return (
        <HydrationBoundary state={dehydrate(queryClient)}>
            <GitHubView orgId={orgId} orgName={organization.name} />
        </HydrationBoundary>
    );
}
