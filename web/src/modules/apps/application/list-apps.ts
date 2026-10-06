import type { OrgId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import type { App } from "../domain/app";
import type { AppListQuery } from "../domain/app-list-query";
import type { AppRepository } from "./ports";

export type ListApps = (orgId: OrgId, query: AppListQuery) => Promise<Page<App>>;

export function makeListApps(apps: AppRepository): ListApps {
    return (orgId, query) => apps.list(orgId, query);
}
