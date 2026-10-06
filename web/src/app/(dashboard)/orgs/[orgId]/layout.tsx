import { getServerUseCases, rethrowServerFailure } from "@/composition/server";
import { memberQueries, OrgAccessProvider } from "@/modules/members";
import {
    isOrganizationUnavailable,
    organizationQueries,
    orgIdFromParam,
    RememberLastOrganization,
} from "@/modules/organizations";
import { getServerQueryClient } from "@/shared/presentation/query/get-server-query-client";
import { dehydrate, HydrationBoundary } from "@tanstack/react-query";
import { notFound } from "next/navigation";

export default async function OrganizationLayout({ children, params }: LayoutProps<"/orgs/[orgId]">) {
    const orgId = orgIdFromParam((await params).orgId);
    const { organizations, members } = getServerUseCases();
    const queryClient = getServerQueryClient();

    const [organization, membership] = await Promise.allSettled([
        queryClient.query(organizationQueries.detail(organizations.getOrganization, orgId)),
        queryClient.query(memberQueries.mine(members.getMyMembership, orgId)),
    ]);
    const failures = [organization, membership].flatMap((result): unknown[] =>
        result.status === "rejected" ? [result.reason] : [],
    );

    if (failures.some((failure) => isOrganizationUnavailable(failure))) notFound();
    if (organization.status === "rejected" || failures.length > 0) return rethrowServerFailure(failures[0]);

    return (
        <HydrationBoundary state={dehydrate(queryClient)}>
            <OrgAccessProvider orgId={orgId} orgKind={organization.value.kind}>
                <RememberLastOrganization orgId={orgId} />
                {children}
            </OrgAccessProvider>
        </HydrationBoundary>
    );
}
