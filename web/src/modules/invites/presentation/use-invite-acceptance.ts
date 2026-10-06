"use client";

import { authPaths } from "@/modules/session";
import { queryScopes } from "@/shared/presentation/query";
import { useMutation, useQuery, useQueryClient, type UseMutationResult } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useTransition } from "react";
import type { AcceptOutcome } from "../application/accept-invite";
import { useInviteUseCases } from "./invite-use-cases";
import { inviteQueries } from "./queries";

export function useInvitePreview(token: string) {
    const { previewInvite } = useInviteUseCases();
    return useQuery(inviteQueries.preview(previewInvite, token));
}

export type UnacceptedOutcome = Exclude<AcceptOutcome, { readonly kind: "accepted" }>;

export interface AcceptCallbacks {
    /** Acceptance leaves for sign-in on its own; every other outcome is handed back. */
    readonly onOutcome: (outcome: UnacceptedOutcome) => void;
    readonly onError: (error: unknown) => void;
}

export interface AcceptWithNewAccountController {
    readonly accept: (password: string, callbacks: AcceptCallbacks) => void;
    readonly pending: boolean;
}

/** Creating the account never signs anyone in, so a new invitee goes on to sign in with it. */
export function useAcceptWithNewAccount(token: string, orgName: string): AcceptWithNewAccountController {
    const { acceptWithNewAccount } = useInviteUseCases();
    const router = useRouter();
    const [navigating, startNavigation] = useTransition();
    const mutation = useMutation({
        mutationFn: (password: string) => acceptWithNewAccount(token, password),
        gcTime: 0,
    });

    return {
        accept: (password, { onOutcome, onError }) => {
            mutation.mutate(password, {
                onSuccess: (outcome) => {
                    mutation.reset();
                    if (outcome.kind !== "accepted") {
                        onOutcome(outcome);
                        return;
                    }
                    startNavigation(() => {
                        router.replace(authPaths.signIn({ joined: orgName }));
                    });
                },
                onError,
            });
        },
        pending: mutation.isPending || navigating,
    };
}

/** On acceptance my organizations are read again, so the new membership shows up once it is projected. */
export function useAcceptAsSignedIn(token: string): UseMutationResult<AcceptOutcome, unknown, void> {
    const { acceptAsSignedIn } = useInviteUseCases();
    const queryClient = useQueryClient();

    return useMutation({
        mutationFn: () => acceptAsSignedIn(token),
        onSuccess: async (outcome) => {
            if (outcome.kind === "accepted") {
                await queryClient.invalidateQueries({ queryKey: queryScopes.myOrganizations() });
            }
        },
    });
}
