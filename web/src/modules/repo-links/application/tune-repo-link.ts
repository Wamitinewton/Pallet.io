import { ApiError } from "@/shared/domain/errors";
import type { OrgId } from "@/shared/domain/ids";
import { buildSettingsChange, buildSettingsSchema, isNoChange, type BuildSettingsForm } from "../domain/build-settings";
import type { RepoLink } from "../domain/repo-link";
import { CONCURRENT_MODIFICATION } from "../domain/repo-link-errors";
import type { RepoLinkRepository } from "./ports";

export interface TuneRepoLinkCommand {
    readonly orgId: OrgId;
    /** The link as the form last read it: what counts as a change, and the version sent back. */
    readonly link: RepoLink;
    readonly settings: BuildSettingsForm;
}

/** Someone else saved first: the link as it is now, or null once it is gone, for the edits to be made again. */
export type TuneRepoLinkOutcome =
    | { readonly status: "updated"; readonly link: RepoLink }
    | { readonly status: "changedMeanwhile"; readonly latest: RepoLink | null };

/** Settings the link already has change nothing, so nothing is sent. */
export type TuneRepoLink = (command: TuneRepoLinkCommand) => Promise<TuneRepoLinkOutcome>;

export function makeTuneRepoLink(links: RepoLinkRepository): TuneRepoLink {
    return async ({ orgId, link, settings }) => {
        const change = buildSettingsChange(link, buildSettingsSchema.parse(settings));
        if (isNoChange(change)) return { status: "updated", link };
        try {
            return { status: "updated", link: await links.tune(orgId, link.appId, change, link.version) };
        } catch (error) {
            if (!(error instanceof ApiError && error.is(CONCURRENT_MODIFICATION))) throw error;
            return { status: "changedMeanwhile", latest: await links.find(orgId, link.appId) };
        }
    };
}
