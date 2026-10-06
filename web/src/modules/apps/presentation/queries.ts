import type { AppId, OrgId } from "@/shared/domain/ids";
import { queryScopes } from "@/shared/presentation/query";
import { queryOptions } from "@tanstack/react-query";
import type { GetApp } from "../application/get-app";
import type { ListApps } from "../application/list-apps";
import type { AppListQuery } from "../domain/app-list-query";

export const appKeys = {
    all: queryScopes.apps,
    /** Every page of every apps list, the team page's included, so one change to an app reaches them all. */
    lists: (orgId: OrgId) => [...appKeys.all(orgId), "list"] as const,
    list: (orgId: OrgId, query: AppListQuery) => [...appKeys.lists(orgId), query] as const,
    /** Everything about one app, its repository link included: dropped whole when the app is deleted. */
    app: (orgId: OrgId, appId: AppId) => [...appKeys.all(orgId), "app", appId] as const,
    detail: (orgId: OrgId, appId: AppId) => [...appKeys.app(orgId, appId), "detail"] as const,
};

export const appQueries = {
    list: (listApps: ListApps, orgId: OrgId, query: AppListQuery) =>
        queryOptions({
            queryKey: appKeys.list(orgId, query),
            queryFn: () => listApps(orgId, query),
        }),
    detail: (getApp: GetApp, orgId: OrgId, appId: AppId) =>
        queryOptions({
            queryKey: appKeys.detail(orgId, appId),
            queryFn: () => getApp(orgId, appId),
        }),
};
