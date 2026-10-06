import { ApiError } from "@/shared/domain/errors";
import { asAppId, asTeamId, isUuid, type AppId, type TeamId } from "@/shared/domain/ids";
import type { IsoInstant } from "@/shared/domain/instant";
import { optionalSlugSchema } from "@/shared/domain/slug";
import { z } from "zod";
import { APP_NOT_FOUND } from "./app-errors";
import { CLOUD_PROVIDERS, isRegionOf, type CloudProvider } from "./region-catalog";

export interface App {
    readonly id: AppId;
    readonly name: string;
    /** Set at creation and never changed; reusable once the app is deleted. */
    readonly slug: string;
    /** Fixed at creation, like the region. */
    readonly cloudProvider: CloudProvider;
    readonly region: string;
    readonly teamId: TeamId | null;
    readonly createdAt: IsoInstant;
    readonly updatedAt: IsoInstant;
}

export const MAX_APP_NAME_LENGTH = 100;

export const appNameSchema = z
    .string()
    .trim()
    .min(1, "Enter a name for the app")
    .max(MAX_APP_NAME_LENGTH, `Keep the name to ${String(MAX_APP_NAME_LENGTH)} characters or fewer`);

/** A team select's value, where empty means no team; accepts its own output, so parsing twice changes nothing. */
export const teamChoiceSchema = z
    .string()
    .nullable()
    .transform((value) => (value === null || value === "" ? null : asTeamId(value)));

export const newAppSchema = z
    .object({
        name: appNameSchema,
        slug: optionalSlugSchema,
        cloudProvider: z.enum(CLOUD_PROVIDERS, "Choose where the app runs"),
        region: z.string().min(1, "Choose a region"),
        teamId: teamChoiceSchema,
    })
    .refine(({ cloudProvider, region }) => isRegionOf(cloudProvider, region), {
        path: ["region"],
        message: "Choose a region from the list",
    });

export type NewAppForm = z.input<typeof newAppSchema>;

export type NewApp = z.output<typeof newAppSchema>;

/** Exactly the app's slug as shown: no trimming, no case folding. */
export function confirmsAppDeletion(app: Pick<App, "slug">, typed: string): boolean {
    return typed === app.slug;
}

export class AppDeletionNotConfirmedError extends Error {
    override readonly name = "AppDeletionNotConfirmedError";

    constructor() {
        super("The typed confirmation doesn't match the app's slug");
    }
}

/** The `[appId]` route segment; anything but a UUID can't name an app, so it never reaches the backend. */
export function appIdFromParam(value: string): AppId | undefined {
    return isUuid(value) ? asAppId(value) : undefined;
}

/** The app is gone, or never existed: the page draws its not-found state for either. */
export function isAppUnavailable(error: unknown): boolean {
    return error instanceof ApiError && error.is(APP_NOT_FOUND);
}
