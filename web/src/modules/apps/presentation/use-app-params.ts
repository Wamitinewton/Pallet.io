"use client";

import { useQueryState, useQueryStates } from "nuqs";
import { useCallback } from "react";
import { withFilters, type AppFilterChange, type AppListParams } from "../domain/app-list-query";
import { appSearchParams, appTabParam, type AppPageTab } from "./search-params";

export interface AppListParamsState {
    readonly params: AppListParams;
    /** Changes what the list shows and goes back to its first page. */
    readonly changeFilters: (change: AppFilterChange) => void;
    readonly goToPage: (page: number) => void;
}

export function useAppListParams(): AppListParamsState {
    const [params, setParams] = useQueryStates(appSearchParams);

    const changeFilters = useCallback(
        (change: AppFilterChange) => {
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

export function useAppTab(): readonly [AppPageTab, (tab: AppPageTab) => void] {
    const [tab, setTab] = useQueryState("tab", appTabParam);
    const selectTab = useCallback(
        (next: AppPageTab) => {
            void setTab(next);
        },
        [setTab],
    );
    return [tab, selectTab];
}
