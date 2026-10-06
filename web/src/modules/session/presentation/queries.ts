import { queryOptions } from "@tanstack/react-query";
import type { ReadSessionSummary } from "../application/read-session-summary";

export const sessionKeys = {
    all: ["session"] as const,
    summary: () => [...sessionKeys.all, "summary"] as const,
};

export const sessionQueries = {
    summary: (readSummary: ReadSessionSummary) =>
        queryOptions({
            queryKey: sessionKeys.summary(),
            queryFn: () => readSummary(),
        }),
};
