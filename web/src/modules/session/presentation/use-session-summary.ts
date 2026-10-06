"use client";

import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import type { SessionSummary } from "../domain/session";
import { sessionQueries } from "./queries";
import { useSessionActions } from "./session-actions";

export function useSessionSummary(): UseQueryResult<SessionSummary> {
    const { readSummary } = useSessionActions();
    return useQuery(sessionQueries.summary(readSummary));
}
