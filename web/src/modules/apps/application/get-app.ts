import type { AppId, OrgId } from "@/shared/domain/ids";
import type { App } from "../domain/app";
import type { AppRepository } from "./ports";

export type GetApp = (orgId: OrgId, appId: AppId) => Promise<App>;

export function makeGetApp(apps: AppRepository): GetApp {
    return (orgId, appId) => apps.get(orgId, appId);
}
