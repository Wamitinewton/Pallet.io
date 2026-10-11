import { getServerUseCases } from "@/composition/server";
import { loadNotificationSearchParams, notificationQueries, NotificationsView } from "@/modules/notifications";
import { getServerQueryClient } from "@/shared/presentation/query/get-server-query-client";
import { dehydrate, HydrationBoundary } from "@tanstack/react-query";
import type { Metadata } from "next";

export const metadata: Metadata = {
    title: "Notifications",
};

/** The list draws its own error state, so a failed prefetch only costs a client fetch. */
const ignore = () => undefined;

export default async function NotificationsPage({ searchParams }: PageProps<"/notifications">) {
    const { notifications } = getServerUseCases();
    const queryClient = getServerQueryClient();
    const { status } = await loadNotificationSearchParams(searchParams);
    await queryClient.infiniteQuery(notificationQueries.list(notifications.listNotifications, status)).catch(ignore);

    return (
        <HydrationBoundary state={dehydrate(queryClient)}>
            <NotificationsView />
        </HydrationBoundary>
    );
}
