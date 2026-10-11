"use client";

import type { OrgId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import type { App } from "../domain/app";
import type { AppListQuery } from "../domain/app-list-query";
import { useAppUseCases } from "./app-use-cases";
import { appQueries } from "./queries";

export function useAppList(orgId: OrgId, query: AppListQuery): UseQueryResult<Page<App>> {
    const { listApps } = useAppUseCases();
    return useQuery(appQueries.list(listApps, orgId, query));
}
