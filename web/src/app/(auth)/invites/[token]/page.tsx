import { features } from "@/composition/features";
import { getPublicServerUseCases, hasActiveSession, readServerSessionSummary } from "@/composition/server";
import { InviteAcceptScreen, inviteQueries } from "@/modules/invites";
import { sessionQueries } from "@/modules/session";
import { getServerQueryClient } from "@/shared/presentation/query/get-server-query-client";
import { dehydrate, HydrationBoundary } from "@tanstack/react-query";
import type { Metadata } from "next";

export const metadata: Metadata = {
    title: "Accept your invite",
    referrer: "no-referrer",
    robots: { index: false, follow: false },
};

/** The view draws its own error state, so a failed prefetch only costs a client fetch. */
const ignore = () => undefined;

export default async function InvitePage({ params }: PageProps<"/invites/[token]">) {
    const { token } = await params;
    const signedIn = await hasActiveSession();
    const queryClient = getServerQueryClient();

    await Promise.all([
        queryClient.query(inviteQueries.preview(getPublicServerUseCases().invites.previewInvite, token)).catch(ignore),
        signedIn && queryClient.query(sessionQueries.summary(readServerSessionSummary)).catch(ignore),
    ]);

    return (
        <HydrationBoundary state={dehydrate(queryClient)}>
            <InviteAcceptScreen token={token} signedIn={signedIn} canJoinAsSignedIn={features.inviteExistingAccount} />
        </HydrationBoundary>
    );
}
