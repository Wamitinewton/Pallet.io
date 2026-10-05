"use client";

import { authPaths } from "@/modules/session";
import { useMutation } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useTransition } from "react";
import type { PasswordReset } from "../domain/reset-password";
import { useIdentityUseCases } from "./identity-use-cases";
import type { MutationCallbacks } from "./use-verify-email";

export interface RequestPasswordResetController {
    readonly request: (email: string, callbacks: MutationCallbacks) => void;
    readonly pending: boolean;
}

export function useRequestPasswordReset(): RequestPasswordResetController {
    const { requestPasswordReset } = useIdentityUseCases();
    const mutation = useMutation({ mutationFn: requestPasswordReset });

    return {
        request: (email, callbacks) => {
            mutation.mutate(email, callbacks);
        },
        pending: mutation.isPending,
    };
}

export interface ResetPasswordController {
    readonly reset: (reset: PasswordReset, callbacks: Pick<MutationCallbacks, "onError">) => void;
    readonly pending: boolean;
}

/** Sets the new password, then sends the person to sign in with it; a reset never signs anyone in. */
export function useResetPassword(): ResetPasswordController {
    const { resetPassword } = useIdentityUseCases();
    const router = useRouter();
    const [navigating, startNavigation] = useTransition();
    const mutation = useMutation({ mutationFn: resetPassword, gcTime: 0 });

    return {
        reset: (reset, { onError }) => {
            mutation.mutate(reset, {
                onSuccess: () => {
                    mutation.reset();
                    startNavigation(() => {
                        router.replace(authPaths.signIn({ reset: true }));
                    });
                },
                onError,
            });
        },
        pending: mutation.isPending || navigating,
    };
}
