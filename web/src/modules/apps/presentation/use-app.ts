"use client";

import type { AppId, OrgId } from "@/shared/domain/ids";
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import type { App } from "../domain/app";
import { useAppUseCases } from "./app-use-cases";
import { appQueries } from "./queries";

export function useApp(orgId: OrgId, appId: AppId): UseQueryResult<App> {
    const { getApp } = useAppUseCases();
    return useQuery(appQueries.detail(getApp, orgId, appId));
}
