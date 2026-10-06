import { ApiError, NetworkError, SessionExpiredError, UnexpectedResponseError } from "@/shared/domain/errors";
import type { QueryClient } from "@tanstack/react-query";
import { describe, expect, it, vi } from "vitest";
import { createQueryClient, MAX_QUERY_RETRIES, shouldRetryQuery, subscribeToSessionExpiry } from "./query-client";

const apiError = (status: number) => new ApiError(status, "CODE", "message", [], {}, undefined);

describe("shouldRetryQuery", () => {
    it.each([
        ["a 4xx ApiError", apiError(404), false],
        ["a 429 ApiError", apiError(429), false],
        ["SessionExpiredError", new SessionExpiredError(), false],
        ["a 5xx ApiError", apiError(503), true],
        ["NetworkError", new NetworkError(), true],
        ["UnexpectedResponseError", new UnexpectedResponseError(502, "Bad gateway", undefined), true],
        ["an unknown error", new Error("bug"), false],
    ])("%s retries: %s", (_, error, retries) => {
        expect(shouldRetryQuery(0, error)).toBe(retries);
    });

    it("stops after two retries", () => {
        expect(MAX_QUERY_RETRIES).toBe(2);
        expect(shouldRetryQuery(1, new NetworkError())).toBe(true);
        expect(shouldRetryQuery(2, new NetworkError())).toBe(false);
    });
});

describe("createQueryClient", () => {
    it("never retries a mutation", () => {
        expect(createQueryClient().getDefaultOptions().mutations?.retry).toBe(false);
    });

    it("leaves a pending query out of the snapshot, so a layout never ships its page's prefetch half-done", () => {
        const client = createQueryClient();
        const shouldDehydrate = client.getDefaultOptions().dehydrate?.shouldDehydrateQuery;
        void client.query({ queryKey: ["slow"], queryFn: () => new Promise(() => undefined) });
        const query = client.getQueryCache().find({ queryKey: ["slow"] });

        expect(query && shouldDehydrate?.(query)).toBe(false);
    });
});

describe("subscribeToSessionExpiry", () => {
    const client = () => {
        const queryClient = createQueryClient();
        queryClient.setDefaultOptions({ queries: { retry: false } });
        return queryClient;
    };

    it("calls the listener when a query or a mutation fails with SessionExpiredError", async () => {
        const queryClient = client();
        const listener = vi.fn();
        subscribeToSessionExpiry(queryClient, listener);

        await queryClient
            .query({ queryKey: ["q"], queryFn: () => Promise.reject(new SessionExpiredError()) })
            .catch(() => undefined);
        await queryClient
            .getMutationCache()
            .build(queryClient, { mutationFn: () => Promise.reject(new SessionExpiredError()) })
            .execute(undefined)
            .catch(() => undefined);

        expect(listener).toHaveBeenCalledTimes(2);
    });

    it("ignores every other failure and stops after unsubscribing", async () => {
        const queryClient: QueryClient = client();
        const listener = vi.fn();
        const unsubscribe = subscribeToSessionExpiry(queryClient, listener);

        await queryClient
            .query({ queryKey: ["a"], queryFn: () => Promise.reject(apiError(403)) })
            .catch(() => undefined);
        unsubscribe();
        await queryClient
            .query({ queryKey: ["b"], queryFn: () => Promise.reject(new SessionExpiredError()) })
            .catch(() => undefined);

        expect(listener).not.toHaveBeenCalled();
    });
});
