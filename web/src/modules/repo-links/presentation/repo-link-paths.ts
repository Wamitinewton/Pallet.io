import { appPath } from "@/modules/apps";
import type { AppId, OrgId } from "@/shared/domain/ids";
import type { Route } from "next";

export function connectRepositoryPath(orgId: OrgId, appId: AppId): Route {
    return `${appPath(orgId, appId)}/connect` as Route;
}
