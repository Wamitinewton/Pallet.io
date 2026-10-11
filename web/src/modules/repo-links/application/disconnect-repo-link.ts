import type { AppId, OrgId } from "@/shared/domain/ids";
import type { RepoLinkRepository } from "./ports";

/** Pushes stop building; the app and its settings stay, and nothing changes on GitHub. */
export type DisconnectRepoLink = (orgId: OrgId, appId: AppId) => Promise<void>;

export function makeDisconnectRepoLink(links: RepoLinkRepository): DisconnectRepoLink {
    return (orgId, appId) => links.disconnect(orgId, appId);
}
