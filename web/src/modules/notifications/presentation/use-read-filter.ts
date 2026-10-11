"use client";

import { useQueryStates } from "nuqs";
import { useCallback } from "react";
import type { ReadFilter } from "../domain/notification";
import { notificationSearchParams } from "./search-params";

export function useReadFilter(): readonly [ReadFilter, (filter: ReadFilter) => void] {
    const [{ status }, setParams] = useQueryStates(notificationSearchParams);
    const show = useCallback(
        (next: ReadFilter) => {
            void setParams({ status: next });
        },
        [setParams],
    );
    return [status, show];
}
