import type { AppId, OrgId } from "@/shared/domain/ids";
import type { RepoLink } from "../domain/repo-link";
import type { RepoLinkRepository } from "./ports";

/** For handing over before the current verifier leaves the GitHub organization; a failed check changes nothing. */
export type TakeOverVerification = (orgId: OrgId, appId: AppId) => Promise<RepoLink>;

export function makeTakeOverVerification(links: RepoLinkRepository): TakeOverVerification {
    return (orgId, appId) => links.takeOverVerification(orgId, appId);
}
