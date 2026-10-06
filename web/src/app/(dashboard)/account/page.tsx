import { getServerUseCases, readServerSessionSummary } from "@/composition/server";
import { AccountView, identityQueries } from "@/modules/identity";
import { sessionQueries } from "@/modules/session";
import { getServerQueryClient } from "@/shared/presentation/query/get-server-query-client";
import { dehydrate, HydrationBoundary } from "@tanstack/react-query";
import type { Metadata } from "next";

export const metadata: Metadata = {
    title: "Your account",
};

/** A failed prefetch only costs a client fetch; the sessions panel draws its own error state. */
const ignore = () => undefined;

export default async function AccountPage() {
    const { identity } = getServerUseCases();
    const queryClient = getServerQueryClient();
    await Promise.all([
        queryClient.query(identityQueries.sessions(identity.listSessions)).catch(ignore),
        queryClient.query(sessionQueries.summary(readServerSessionSummary)).catch(ignore),
    ]);

    return (
        <HydrationBoundary state={dehydrate(queryClient)}>
            <AccountView />
        </HydrationBoundary>
    );
}
