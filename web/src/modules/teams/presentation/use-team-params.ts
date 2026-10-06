"use client";

import { useQueryStates } from "nuqs";
import { useCallback } from "react";
import type { TeamListParams, TeamSort } from "../domain/team-list-query";
import { teamMemberSearchParams, teamSearchParams } from "./search-params";

export interface TeamListParamsState {
    readonly params: TeamListParams;
    /** Changes the order and goes back to the first page. */
    readonly changeSort: (sort: TeamSort) => void;
    readonly goToPage: (page: number) => void;
}

export function useTeamListParams(): TeamListParamsState {
    const [params, setParams] = useQueryStates(teamSearchParams);

    const changeSort = useCallback(
        (sort: TeamSort) => {
            void setParams({ sort, page: 1 });
        },
        [setParams],
    );

    const goToPage = useCallback(
        (page: number) => {
            void setParams({ page }, { history: "push", scroll: true });
        },
        [setParams],
    );

    return { params, changeSort, goToPage };
}

export function useTeamMemberPage(): readonly [number, (page: number) => void] {
    const [{ page }, setParams] = useQueryStates(teamMemberSearchParams);
    const goToPage = useCallback(
        (next: number) => {
            void setParams({ page: next }, { history: "push" });
        },
        [setParams],
    );
    return [page, goToPage];
}
