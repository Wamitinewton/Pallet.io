import { z } from "zod";
import { branchNameSchema } from "./branch-name";
import type { BuildSettings } from "./repo-link";
import { REPOSITORY_ROOT, rootDirectorySchema } from "./root-directory";

/** Shared by linking a repository and tuning a link, so both refuse the same values in the same words. */
export const buildSettingsSchema = z.object({
    productionBranch: branchNameSchema,
    rootDirectory: rootDirectorySchema,
    autoDeploy: z.boolean(),
});

export type BuildSettingsForm = z.input<typeof buildSettingsSchema>;

export const BUILD_SETTING_FIELDS = ["productionBranch", "rootDirectory", "autoDeploy"] as const;

export type BuildSettingField = (typeof BUILD_SETTING_FIELDS)[number];

/** A `PATCH` body without its version: an absent field stays as it is. */
export type BuildSettingsChange = Partial<BuildSettings>;

export function buildSettingsOf({ productionBranch, rootDirectory, autoDeploy }: BuildSettings): BuildSettingsForm {
    return { productionBranch, rootDirectory, autoDeploy };
}

/** A new link builds from the repository's default branch, at its root, on every push. */
export function newLinkSettings(defaultBranch: string): BuildSettingsForm {
    return { productionBranch: defaultBranch, rootDirectory: REPOSITORY_ROOT, autoDeploy: true };
}

/** Only what differs from the link as last read, so an untouched field is never sent. */
export function buildSettingsChange(current: BuildSettings, next: BuildSettings): BuildSettingsChange {
    return {
        ...(current.productionBranch !== next.productionBranch && { productionBranch: next.productionBranch }),
        ...(current.rootDirectory !== next.rootDirectory && { rootDirectory: next.rootDirectory }),
        ...(current.autoDeploy !== next.autoDeploy && { autoDeploy: next.autoDeploy }),
    };
}

export function changedFields(change: BuildSettingsChange): readonly BuildSettingField[] {
    return BUILD_SETTING_FIELDS.filter((field) => change[field] !== undefined);
}

export function isNoChange(change: BuildSettingsChange): boolean {
    return changedFields(change).length === 0;
}
