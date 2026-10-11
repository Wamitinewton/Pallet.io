import type { AppId, InstallationId, OrgId, RepoId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import type { BuildSettingsChange } from "../domain/build-settings";
import type { ManualBuild, ManualBuildRequest } from "../domain/manual-build";
import type { PickerRepository, RepositoryQuery } from "../domain/picker-repository";
import type { BuildSettings, RepoLink } from "../domain/repo-link";

export interface LinkRequest {
    readonly installationId: InstallationId;
    readonly repoId: RepoId;
    readonly settings: BuildSettings;
    /** Build the branch's current head right away, as well as on later pushes when `autoDeploy` is on. */
    readonly deployNow: boolean;
}

export interface RepoLinkRepository {
    /** Null when the app has never been linked; a disconnected link is still a link. */
    find(orgId: OrgId, appId: AppId): Promise<RepoLink | null>;
    /** Needs the caller's GitHub session and push access to the repository. */
    link(orgId: OrgId, appId: AppId, request: LinkRequest): Promise<RepoLink>;
    /** Refused with `CONCURRENT_MODIFICATION` when `version` is no longer the link's. */
    tune(orgId: OrgId, appId: AppId, change: BuildSettingsChange, version: number): Promise<RepoLink>;
    /** Makes the caller the verifier, after the same access check as linking. */
    takeOverVerification(orgId: OrgId, appId: AppId): Promise<RepoLink>;
    disconnect(orgId: OrgId, appId: AppId): Promise<void>;
    /** A retry with the same key answers with the first result instead of asking for another build. */
    requestBuild(orgId: OrgId, appId: AppId, request: ManualBuildRequest, idempotencyKey: string): Promise<ManualBuild>;
}

export interface RepositoryPickerRepository {
    /** Only what the caller's own GitHub token reaches through the installation, never its full list. */
    list(orgId: OrgId, installationId: InstallationId, query: RepositoryQuery): Promise<Page<PickerRepository>>;
}
