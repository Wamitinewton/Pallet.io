import { getServerUseCases, lastOrganizationId, requireActiveSession } from "@/composition/server";
import { identityQueries } from "@/modules/identity";
import { notificationQueries } from "@/modules/notifications";
import { chooseLandingOrganization, DashboardShell, organizationQueries } from "@/modules/organizations";
import { SessionExpiryHandler } from "@/modules/session";
import { getServerQueryClient } from "@/shared/presentation/query/get-server-query-client";
import { dehydrate, HydrationBoundary } from "@tanstack/react-query";
import type { Metadata } from "next";
import type { ReactNode } from "react";

/** Every dashboard page is per-session; prerendering one would also demand the server env at build time. */
export const dynamic = "force-dynamic";

export const metadata: Metadata = {
    robots: { index: false, follow: false },
};

/** The shell renders its own empty and error states, so a failed prefetch only costs a client fetch. */
const ignore = () => undefined;

export default async function DashboardLayout({ children }: Readonly<{ children: ReactNode }>) {
    await requireActiveSession();

    const { identity, organizations, notifications } = getServerUseCases();
    const queryClient = getServerQueryClient();
    const myOrganizations = organizationQueries.mine(organizations.listMyOrganizations);
    const [lastOrgId] = await Promise.all([
        lastOrganizationId(),
        queryClient.query(identityQueries.profile(identity.getMyProfile)).catch(ignore),
        queryClient.query(myOrganizations).catch(ignore),
        queryClient.query(notificationQueries.unreadCount(notifications.getUnreadCount)).catch(ignore),
    ]);
    const fallbackOrgId = chooseLandingOrganization(
        queryClient.getQueryData(myOrganizations.queryKey)?.items ?? [],
        lastOrgId,
    );

    return (
        <>
            <SessionExpiryHandler />
            <HydrationBoundary state={dehydrate(queryClient)}>
                <DashboardShell fallbackOrgId={fallbackOrgId}>{children}</DashboardShell>
            </HydrationBoundary>
        </>
    );
}
