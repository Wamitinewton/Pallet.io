"use client";

import { useMutation } from "@tanstack/react-query";
import { SIGN_IN_PATH } from "../domain/sign-in-path";
import { useSessionActions } from "./session-actions";
import { useNavigateAcrossSession } from "./use-navigate-across-session";

export interface SignOutController {
    readonly signOut: () => void;
    readonly pending: boolean;
}

/** Signs out, then leaves for `destination`, which must be a page a signed-out visitor can open. */
export function useSignOut(destination: string = SIGN_IN_PATH): SignOutController {
    const { signOut } = useSessionActions();
    const navigate = useNavigateAcrossSession();
    const mutation = useMutation({
        mutationFn: signOut,
        onSettled: () => {
            navigate(destination);
        },
    });

    return {
        signOut: () => {
            mutation.mutate();
        },
        pending: mutation.isPending,
    };
}
