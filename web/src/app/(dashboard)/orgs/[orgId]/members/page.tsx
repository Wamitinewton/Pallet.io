import { getServerUseCases, rethrowServerFailure } from "@/composition/server";
import {
    inviteListQuery,
    InvitePeopleButton,
    inviteQueries,
    InvitesProvider,
    InvitesTab,
    loadInviteSearchParams,
} from "@/modules/invites";
import { loadMemberSearchParams, memberListQuery, memberQueries, MembersView } from "@/modules/members";
import { organizationPath, organizationQueries, orgIdFromParam } from "@/modules/organizations";
import { getServerQueryClient } from "@/shared/presentation/query/get-server-query-client";
import { dehydrate, HydrationBoundary } from "@tanstack/react-query";
import type { Metadata, Route } from "next";

export const metadata: Metadata = {
    title: "Members",
};

/** Each list draws its own error state, so a failed prefetch only costs a client fetch. */
const ignore = () => undefined;

export default async function MembersPage({ params, searchParams }: PageProps<"/orgs/[orgId]/members">) {
    const orgId = orgIdFromParam((await params).orgId);
    const { organizations, members, invites } = getServerUseCases();
    const queryClient = getServerQueryClient();

    const [organization, membership, listParams, inviteParams] = await Promise.all([
        queryClient.query(organizationQueries.detail(organizations.getOrganization, orgId)),
        queryClient.query(memberQueries.mine(members.getMyMembership, orgId)),
        loadMemberSearchParams(searchParams),
        loadInviteSearchParams(searchParams),
    ]).catch(rethrowServerFailure);

    const viewer = { role: membership.role, orgKind: organization.kind };
    const inviteQuery = listParams.tab === "invites" ? inviteListQuery(inviteParams, viewer) : undefined;
    await Promise.all(
        inviteQuery === undefined
            ? [queryClient.query(memberQueries.list(members.listMembers, orgId, memberListQuery(listParams, viewer)))]
            : [
                  queryClient.query(inviteQueries.list(invites.listInvites, orgId, inviteQuery)),
                  queryClient.query(memberQueries.directory(members.listMembers, orgId)),
              ],
    ).catch(ignore);

    return (
        <HydrationBoundary state={dehydrate(queryClient)}>
            <InvitesProvider orgId={orgId} orgName={organization.name}>
                <MembersView
                    orgId={orgId}
                    orgName={organization.name}
                    orgHref={organizationPath(orgId) as Route}
                    invites={{ action: <InvitePeopleButton />, panel: <InvitesTab /> }}
                />
            </InvitesProvider>
        </HydrationBoundary>
    );
}
