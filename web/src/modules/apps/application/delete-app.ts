import type { OrgId } from "@/shared/domain/ids";
import { AppDeletionNotConfirmedError, confirmsAppDeletion, type App } from "../domain/app";
import type { AppRepository } from "./ports";

export interface DeleteAppCommand {
    readonly orgId: OrgId;
    readonly app: App;
    readonly confirmation: string;
}

/** The backend asks for no confirmation, so the typed slug is checked here and never sent. */
export type DeleteApp = (command: DeleteAppCommand) => Promise<void>;

export function makeDeleteApp(apps: AppRepository): DeleteApp {
    return async ({ orgId, app, confirmation }) => {
        if (!confirmsAppDeletion(app, confirmation)) throw new AppDeletionNotConfirmedError();
        await apps.delete(orgId, app.id);
    };
}
