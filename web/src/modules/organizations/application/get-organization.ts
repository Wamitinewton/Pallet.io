import type { OrgId } from "@/shared/domain/ids";
import type { Organization } from "../domain/organization";
import type { OrganizationRepository } from "./ports";

export type GetOrganization = (orgId: OrgId) => Promise<Organization>;

export function makeGetOrganization(organizations: OrganizationRepository): GetOrganization {
    return (orgId) => organizations.get(orgId);
}
