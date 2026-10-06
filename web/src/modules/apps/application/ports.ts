import type { AppId, OrgId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import type { App, NewApp } from "../domain/app";
import type { AppListQuery } from "../domain/app-list-query";
import type { AppChange } from "../domain/update-app";

export interface AppRepository {
    list(orgId: OrgId, query: AppListQuery): Promise<Page<App>>;
    get(orgId: OrgId, appId: AppId): Promise<App>;
    create(orgId: OrgId, app: NewApp): Promise<App>;
    update(orgId: OrgId, appId: AppId, change: AppChange): Promise<App>;
    /** A soft delete: the slug becomes free for a new app. */
    delete(orgId: OrgId, appId: AppId): Promise<void>;
}
