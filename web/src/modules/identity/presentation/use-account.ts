"use client";

import { useMutation, useQueryClient, type UseMutationResult } from "@tanstack/react-query";
import { withoutSession, type AccountSession, type AccountSessionId } from "../domain/account-session";
import type { PasswordChange } from "../domain/change-password";
import { renamed, type Profile, type ProfileUpdate } from "../domain/profile";
import { useIdentityUseCases } from "./identity-use-cases";
import { identityKeys } from "./queries";

interface Snapshot<T> {
    readonly previous: T | undefined;
}

/** Renames in the cache first, so the page and the user menu change at once; a failure puts the old name back. */
export function useUpdateProfile(): UseMutationResult<void, unknown, ProfileUpdate, Snapshot<Profile>> {
    const { updateProfile } = useIdentityUseCases();
    const queryClient = useQueryClient();
    const queryKey = identityKeys.profile();

    return useMutation({
        mutationFn: updateProfile,
        onMutate: async (update) => {
            await queryClient.cancelQueries({ queryKey });
            const previous = queryClient.getQueryData<Profile>(queryKey);
            if (previous !== undefined) queryClient.setQueryData(queryKey, renamed(previous, update));
            return { previous };
        },
        onError: (_error, _update, snapshot) => {
            if (snapshot?.previous !== undefined) queryClient.setQueryData(queryKey, snapshot.previous);
        },
        onSettled: () => queryClient.invalidateQueries({ queryKey }),
    });
}

/** Keycloak may end other sessions on a credential change, so the list is read again after one. */
export function useChangePassword(): UseMutationResult<void, unknown, PasswordChange> {
    const { changePassword } = useIdentityUseCases();
    const queryClient = useQueryClient();

    return useMutation({
        mutationFn: changePassword,
        gcTime: 0,
        onSuccess: () => queryClient.invalidateQueries({ queryKey: identityKeys.sessions() }),
    });
}

export function useRevokeSession(): UseMutationResult<
    void,
    unknown,
    AccountSessionId,
    Snapshot<readonly AccountSession[]>
> {
    const { revokeSession } = useIdentityUseCases();
    const queryClient = useQueryClient();
    const queryKey = identityKeys.sessions();

    return useMutation({
        mutationFn: revokeSession,
        onMutate: async (id) => {
            await queryClient.cancelQueries({ queryKey });
            const previous = queryClient.getQueryData<readonly AccountSession[]>(queryKey);
            if (previous !== undefined) queryClient.setQueryData(queryKey, withoutSession(previous, id));
            return { previous };
        },
        onError: (_error, _id, snapshot) => {
            if (snapshot?.previous !== undefined) queryClient.setQueryData(queryKey, snapshot.previous);
        },
        onSettled: () => queryClient.invalidateQueries({ queryKey }),
    });
}

export function useRevokeOtherSessions(): UseMutationResult<void, unknown, void> {
    const { revokeOtherSessions } = useIdentityUseCases();
    const queryClient = useQueryClient();

    return useMutation({
        mutationFn: revokeOtherSessions,
        onSettled: () => queryClient.invalidateQueries({ queryKey: identityKeys.sessions() }),
    });
}
