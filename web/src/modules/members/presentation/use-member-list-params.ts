"use client";

import { useQueryStates } from "nuqs";
import { useCallback } from "react";
import { withFilters, type MemberFilterChange, type MemberListParams } from "../domain/member-list-query";
import { memberSearchParams } from "./search-params";

export interface MemberListParamsState {
    readonly params: MemberListParams;
    /** Changes what the list shows and goes back to its first page. */
    readonly changeFilters: (change: MemberFilterChange) => void;
    readonly goToPage: (page: number) => void;
}

export function useMemberListParams(): MemberListParamsState {
    const [params, setParams] = useQueryStates(memberSearchParams);

    const changeFilters = useCallback(
        (change: MemberFilterChange) => {
            void setParams((current) => withFilters(current, change));
        },
        [setParams],
    );

    const goToPage = useCallback(
        (page: number) => {
            void setParams({ page }, { history: "push", scroll: true });
        },
        [setParams],
    );

    return { params, changeFilters, goToPage };
}
