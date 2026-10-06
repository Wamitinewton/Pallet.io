import type { OrgId } from "@/shared/domain/ids";
import { newAppSchema, type App, type NewAppForm } from "../domain/app";
import type { AppRepository } from "./ports";

export type CreateApp = (orgId: OrgId, app: NewAppForm) => Promise<App>;

export function makeCreateApp(apps: AppRepository): CreateApp {
    return async (orgId, app) => apps.create(orgId, newAppSchema.parse(app));
}
