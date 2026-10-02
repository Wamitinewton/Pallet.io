"use client";

import { QueryClientProvider, type QueryClient } from "@tanstack/react-query";
import { ReactQueryDevtools } from "@tanstack/react-query-devtools";
import { useEffect, useState, type ReactNode } from "react";
import { createQueryClient, subscribeToSessionExpiry } from "../query/query-client";

export interface QueryProviderProps {
    readonly children: ReactNode;
    readonly onSessionExpired?: () => void;
    readonly client?: QueryClient;
}

export function QueryProvider({ children, onSessionExpired, client: provided }: QueryProviderProps) {
    const [client] = useState(() => provided ?? createQueryClient());

    useEffect(() => {
        if (onSessionExpired === undefined) return undefined;
        return subscribeToSessionExpiry(client, onSessionExpired);
    }, [client, onSessionExpired]);

    return (
        <QueryClientProvider client={client}>
            {children}
            {process.env.NODE_ENV === "development" && <ReactQueryDevtools buttonPosition="bottom-left" />}
        </QueryClientProvider>
    );
}
