"use client";

import { authPaths } from "@/modules/session";
import { useMutation } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useTransition } from "react";
import { useIdentityUseCases } from "./identity-use-cases";

export interface MutationCallbacks {
    readonly onSuccess?: () => void;
    readonly onError: (error: unknown) => void;
}

export interface VerifyEmailController {
    readonly verify: (code: string, callbacks: MutationCallbacks) => void;
    readonly pending: boolean;
}

/** Confirms the code, then hands over to sign-in with the address prefilled. */
export function useVerifyEmail(email: string): VerifyEmailController {
    const { verifyEmail } = useIdentityUseCases();
    const router = useRouter();
    const [navigating, startNavigation] = useTransition();
    const mutation = useMutation({ mutationFn: (code: string) => verifyEmail({ email, code }) });

    return {
        verify: (code, { onError }) => {
            mutation.mutate(code, {
                onSuccess: () => {
                    startNavigation(() => {
                        router.replace(authPaths.signIn({ verified: true, email }));
                    });
                },
                onError,
            });
        },
        pending: mutation.isPending || navigating,
    };
}

export interface ResendVerificationController {
    readonly resend: (email: string, callbacks: MutationCallbacks) => void;
    readonly pending: boolean;
}

export function useResendVerification(): ResendVerificationController {
    const { resendVerification } = useIdentityUseCases();
    const mutation = useMutation({ mutationFn: resendVerification });

    return {
        resend: (email, callbacks) => {
            mutation.mutate(email, callbacks);
        },
        pending: mutation.isPending,
    };
}
