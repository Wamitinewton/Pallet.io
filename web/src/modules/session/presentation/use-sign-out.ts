"use client";

import { useMutation } from "@tanstack/react-query";
import { SIGN_IN_PATH } from "../domain/sign-in-path";
import { useSessionActions } from "./session-actions";
import { useNavigateAcrossSession } from "./use-navigate-across-session";

export interface SignOutController {
    readonly signOut: () => void;
    readonly pending: boolean;
}

export function useSignOut(): SignOutController {
    const { signOut } = useSessionActions();
    const navigate = useNavigateAcrossSession();
    const mutation = useMutation({
        mutationFn: signOut,
        onSettled: () => {
            navigate(SIGN_IN_PATH);
        },
    });

    return {
        signOut: () => {
            mutation.mutate();
        },
        pending: mutation.isPending,
    };
}
