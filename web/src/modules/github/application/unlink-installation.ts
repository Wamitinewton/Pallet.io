import type { InstallationId, OrgId } from "@/shared/domain/ids";
import type { InstallationRepository } from "./ports";

export type UnlinkInstallation = (orgId: OrgId, installationId: InstallationId) => Promise<void>;

export function makeUnlinkInstallation(installations: InstallationRepository): UnlinkInstallation {
    return (orgId, installationId) => installations.unlink(orgId, installationId);
}
