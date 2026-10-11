import type { AppId, OrgId } from "@/shared/domain/ids";
import { manualBuildSchema, requestedBranch, type ManualBuild, type ManualBuildForm } from "../domain/manual-build";
import type { RepoLinkRepository } from "./ports";

export interface RequestManualBuildCommand {
    readonly orgId: OrgId;
    readonly appId: AppId;
    readonly productionBranch: string;
    readonly form: ManualBuildForm;
    /** One per intent, reused across retries of it, so a retry after a timeout never starts a second build. */
    readonly idempotencyKey: string;
}

export type RequestManualBuild = (command: RequestManualBuildCommand) => Promise<ManualBuild>;

export function makeRequestManualBuild(links: RepoLinkRepository): RequestManualBuild {
    return async ({ orgId, appId, productionBranch, form, idempotencyKey }) => {
        const branch = requestedBranch(manualBuildSchema.parse(form).branch, productionBranch);
        return links.requestBuild(orgId, appId, branch === undefined ? {} : { branch }, idempotencyKey);
    };
}
