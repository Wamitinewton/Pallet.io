"use client";

import { subscribeToSessionExpiry } from "@/shared/presentation/query";
import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef } from "react";
import { signInPath } from "../domain/sign-in-path";
import { useNavigateAcrossSession } from "./use-navigate-across-session";

/** Mounted once per signed-in tree: the first `SessionExpiredError` sends the person to sign in, with a way back. */
export function SessionExpiryHandler() {
    const queryClient = useQueryClient();
    const navigate = useNavigateAcrossSession();
    const handled = useRef(false);

    useEffect(
        () =>
            subscribeToSessionExpiry(queryClient, () => {
                if (handled.current) return;
                handled.current = true;
                const { pathname, search } = window.location;
                navigate(signInPath({ reason: "expired", next: `${pathname}${search}` }));
            }),
        [queryClient, navigate],
    );

    return null;
}
