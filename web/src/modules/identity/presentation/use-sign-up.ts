"use client";

import { authPaths } from "@/modules/session";
import { useMutation } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useTransition } from "react";
import type { SignupDetails } from "../domain/signup";
import { useIdentityUseCases } from "./identity-use-cases";

interface SignUpVariables {
    readonly details: SignupDetails;
    readonly idempotencyKey: string;
}

export interface SignUpCallbacks {
    readonly onError: (error: unknown) => void;
}

export interface SignUpController {
    readonly signUp: (details: SignupDetails, idempotencyKey: string, callbacks: SignUpCallbacks) => void;
    readonly pending: boolean;
}

/** Signs up and moves on to email confirmation; never signs in, since the backend refuses until the email is confirmed. */
export function useSignUp(): SignUpController {
    const { signUp } = useIdentityUseCases();
    const router = useRouter();
    const [navigating, startNavigation] = useTransition();
    const mutation = useMutation({
        mutationFn: ({ details, idempotencyKey }: SignUpVariables) => signUp(details, idempotencyKey),
        gcTime: 0,
    });

    return {
        signUp: (details, idempotencyKey, { onError }) => {
            mutation.mutate(
                { details, idempotencyKey },
                {
                    onSuccess: () => {
                        mutation.reset();
                        startNavigation(() => {
                            router.replace(authPaths.verifyEmail(details.email));
                        });
                    },
                    onError,
                },
            );
        },
        pending: mutation.isPending || navigating,
    };
}
