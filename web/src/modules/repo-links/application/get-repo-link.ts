import type { AppId, OrgId } from "@/shared/domain/ids";
import type { RepoLink } from "../domain/repo-link";
import type { RepoLinkRepository } from "./ports";

export type GetRepoLink = (orgId: OrgId, appId: AppId) => Promise<RepoLink | null>;

export function makeGetRepoLink(links: RepoLinkRepository): GetRepoLink {
    return (orgId, appId) => links.find(orgId, appId);
}
