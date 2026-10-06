"use client";

import { useQueryErrorResetBoundary } from "@tanstack/react-query";
import { Page } from "../ui";
import { ErrorState } from "./ErrorState";

export interface RouteErrorProps {
    readonly error: Error & { digest?: string };
    readonly retry: () => void;
}

/** A route's `error.tsx`: retrying clears the failed queries first, so they fetch again instead of rethrowing. */
export function RouteError({ error, retry }: RouteErrorProps) {
    const { reset } = useQueryErrorResetBoundary();
    return (
        <Page>
            <ErrorState
                error={error}
                title="This page couldn't load"
                onRetry={() => {
                    reset();
                    retry();
                }}
            />
        </Page>
    );
}
