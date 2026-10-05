"use client";

import { useMutation } from "@tanstack/react-query";
import { useTransition } from "react";
import type { Credentials } from "../domain/credentials";
import { safeRedirect } from "../domain/safe-redirect";
import { useSessionActions } from "./session-actions";
import { useNavigateAcrossSession } from "./use-navigate-across-session";

export interface SignInCallbacks {
    readonly onError: (error: unknown) => void;
}

export interface SignInController {
    readonly signIn: (credentials: Credentials, callbacks: SignInCallbacks) => void;
    readonly pending: boolean;
}

export function useSignIn(next: string | undefined): SignInController {
    const { signIn } = useSessionActions();
    const navigate = useNavigateAcrossSession();
    const [navigating, startNavigation] = useTransition();
    const mutation = useMutation({
        mutationFn: signIn,
        onSuccess: () => {
            startNavigation(() => {
                navigate(safeRedirect(next));
            });
        },
    });

    return {
        signIn: (credentials, { onError }) => {
            mutation.mutate(credentials, { onError });
        },
        pending: mutation.isPending || navigating,
    };
}
