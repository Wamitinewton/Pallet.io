import type { OrgId } from "@/shared/domain/ids";
import { renameOrganizationSchema, type Organization, type RenameOrganizationForm } from "../domain/organization";
import type { OrganizationRepository } from "./ports";

export type RenameOrganization = (orgId: OrgId, rename: RenameOrganizationForm) => Promise<Organization>;

export function makeRenameOrganization(organizations: OrganizationRepository): RenameOrganization {
    return async (orgId, rename) => organizations.rename(orgId, renameOrganizationSchema.parse(rename));
}
