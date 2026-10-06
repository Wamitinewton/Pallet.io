import type { TeamId } from "@/shared/domain/ids";
import { z } from "zod";
import { appNameSchema, teamChoiceSchema, type App } from "./app";

export const appSettingsSchema = z.object({ name: appNameSchema, teamId: teamChoiceSchema });

export type AppSettingsForm = z.input<typeof appSettingsSchema>;

export type AppSettings = z.output<typeof appSettingsSchema>;

/**
 * A `PATCH` body: an absent field stays as it is, and `teamId: null` detaches the app from its team. The
 * provider and region have no place here, since they never change.
 */
export interface AppChange {
    readonly name?: string;
    readonly teamId?: TeamId | null;
}

export function appSettingsOf(app: Pick<App, "name" | "teamId">): AppSettingsForm {
    return { name: app.name, teamId: app.teamId ?? "" };
}

/** Only what differs from the app as last read, so an untouched field is never sent. */
export function appChange(app: Pick<App, "name" | "teamId">, settings: AppSettings): AppChange {
    return {
        ...(settings.name !== app.name && { name: settings.name }),
        ...(settings.teamId !== app.teamId && { teamId: settings.teamId }),
    };
}

export function isNoChange(change: AppChange): boolean {
    return change.name === undefined && change.teamId === undefined;
}
