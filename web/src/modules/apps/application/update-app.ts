import { ApiError } from "@/shared/domain/errors";
import type { OrgId } from "@/shared/domain/ids";
import type { App } from "../domain/app";
import { CONCURRENT_MODIFICATION } from "../domain/app-errors";
import { appChange, appSettingsSchema, isNoChange, type AppSettingsForm } from "../domain/update-app";
import type { AppRepository } from "./ports";

export interface UpdateAppCommand {
    readonly orgId: OrgId;
    /** The app as the form last read it, which decides what counts as a change. */
    readonly app: App;
    readonly settings: AppSettingsForm;
}

/** Someone else saved first: the app as it is now, for the person to make their change to again. */
export type UpdateAppOutcome =
    { readonly status: "updated"; readonly app: App } | { readonly status: "changedMeanwhile"; readonly latest: App };

/** Saving settings the app already has changes nothing, so nothing is sent. */
export type UpdateApp = (command: UpdateAppCommand) => Promise<UpdateAppOutcome>;

export function makeUpdateApp(apps: AppRepository): UpdateApp {
    return async ({ orgId, app, settings }) => {
        const change = appChange(app, appSettingsSchema.parse(settings));
        if (isNoChange(change)) return { status: "updated", app };
        try {
            return { status: "updated", app: await apps.update(orgId, app.id, change) };
        } catch (error) {
            if (!(error instanceof ApiError && error.is(CONCURRENT_MODIFICATION))) throw error;
            return { status: "changedMeanwhile", latest: await apps.get(orgId, app.id) };
        }
    };
}
