import type { OrgId } from "@/shared/domain/ids";
import type { LinkOutcome, LinkRequest } from "../domain/installation";
import type { InstallationRepository } from "./ports";

/** The service checks with the caller's own GitHub token that they can see the installation; the client doesn't. */
export type LinkInstallation = (orgId: OrgId, request: LinkRequest) => Promise<LinkOutcome>;

export function makeLinkInstallation(installations: InstallationRepository): LinkInstallation {
    return (orgId, request) => installations.link(orgId, request);
}
