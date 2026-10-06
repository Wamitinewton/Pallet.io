import type { OrgId } from "@/shared/domain/ids";
import type { Page, PageRequest } from "@/shared/domain/page";
import type { InstallationLink } from "../domain/installation";
import type { InstallationRepository } from "./ports";

export type ListOrgInstallations = (orgId: OrgId, page: PageRequest) => Promise<Page<InstallationLink>>;

export function makeListOrgInstallations(installations: InstallationRepository): ListOrgInstallations {
    return (orgId, page) => installations.list(orgId, page);
}
