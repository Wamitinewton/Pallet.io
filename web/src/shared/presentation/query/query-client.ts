import { ApiError, NetworkError, SessionExpiredError, UnexpectedResponseError } from "@/shared/domain/errors";
import { defaultShouldDehydrateQuery, QueryClient } from "@tanstack/react-query";

export const MAX_QUERY_RETRIES = 2;

export function isRetryable(error: unknown): boolean {
    if (error instanceof SessionExpiredError) return false;
    if (error instanceof ApiError) return error.status >= 500;
    return error instanceof NetworkError || error instanceof UnexpectedResponseError;
}

export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
    return failureCount < MAX_QUERY_RETRIES && isRetryable(error);
}

export function retryDelay(attempt: number): number {
    return Math.min(1000 * 2 ** attempt, 10_000);
}

export function subscribeToSessionExpiry(client: QueryClient, listener: () => void): () => void {
    const unsubscribeQueries = client.getQueryCache().subscribe((event) => {
        if (
            event.type === "updated" &&
            event.action.type === "error" &&
            event.action.error instanceof SessionExpiredError
        ) {
            listener();
        }
    });
    const unsubscribeMutations = client.getMutationCache().subscribe((event) => {
        if (
            event.type === "updated" &&
            event.action.type === "error" &&
            event.action.error instanceof SessionExpiredError
        ) {
            listener();
        }
    });
    return () => {
        unsubscribeQueries();
        unsubscribeMutations();
    };
}

export function createQueryClient(): QueryClient {
    return new QueryClient({
        defaultOptions: {
            queries: {
                staleTime: 30_000,
                gcTime: 5 * 60_000,
                refetchOnWindowFocus: true,
                retry: shouldRetryQuery,
                retryDelay,
            },
            mutations: {
                retry: false,
            },
            dehydrate: {
                shouldDehydrateQuery: (query) => defaultShouldDehydrateQuery(query) || query.state.status === "pending",
                shouldRedactErrors: () => false,
            },
        },
    });
}
