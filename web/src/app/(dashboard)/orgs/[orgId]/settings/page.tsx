import { getServerUseCases, readServerSessionSummary } from "@/composition/server";
import { organizationQueries, orgIdFromParam, OrgSettingsView } from "@/modules/organizations";
import { sessionQueries } from "@/modules/session";
import { getServerQueryClient } from "@/shared/presentation/query/get-server-query-client";
import { dehydrate, HydrationBoundary } from "@tanstack/react-query";
import type { Metadata } from "next";

export const metadata: Metadata = {
    title: "Settings",
};

/** The organization itself comes from the layout; these lists draw their own error states. */
const ignore = () => undefined;

export default async function OrganizationSettingsPage({ params }: PageProps<"/orgs/[orgId]/settings">) {
    const orgId = orgIdFromParam((await params).orgId);
    const { organizations } = getServerUseCases();
    const queryClient = getServerQueryClient();
    await Promise.all([
        queryClient.infiniteQuery(organizationQueries.minePages(organizations.listMyOrganizations)).catch(ignore),
        queryClient.query(sessionQueries.summary(readServerSessionSummary)).catch(ignore),
    ]);

    return (
        <HydrationBoundary state={dehydrate(queryClient)}>
            <OrgSettingsView orgId={orgId} />
        </HydrationBoundary>
    );
}
