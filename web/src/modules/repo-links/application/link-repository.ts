import type { AppId, InstallationId, OrgId } from "@/shared/domain/ids";
import { buildSettingsSchema, type BuildSettingsForm } from "../domain/build-settings";
import type { PickerRepository } from "../domain/picker-repository";
import type { RepoLink } from "../domain/repo-link";
import type { RepoLinkRepository } from "./ports";

export interface LinkRepositoryCommand {
    readonly orgId: OrgId;
    readonly appId: AppId;
    readonly installationId: InstallationId;
    readonly repository: Pick<PickerRepository, "repoId">;
    readonly settings: BuildSettingsForm;
    readonly deployNow: boolean;
}

export type LinkRepository = (command: LinkRepositoryCommand) => Promise<RepoLink>;

/** The settings are checked here first, so a bad branch or directory never costs a GitHub check. */
export function makeLinkRepository(links: RepoLinkRepository): LinkRepository {
    return async ({ orgId, appId, installationId, repository, settings, deployNow }) =>
        links.link(orgId, appId, {
            installationId,
            repoId: repository.repoId,
            settings: buildSettingsSchema.parse(settings),
            deployNow,
        });
}
