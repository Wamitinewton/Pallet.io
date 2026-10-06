import type { OrgId } from "@/shared/domain/ids";
import type { Page, PageRequest } from "@/shared/domain/page";
import type { NewOrganization, Organization, OrganizationRename, OrgSummary } from "../domain/organization";

export interface OrganizationRepository {
    listMine(request: PageRequest): Promise<Page<OrgSummary>>;
    get(orgId: OrgId): Promise<Organization>;
    create(organization: NewOrganization): Promise<Organization>;
    rename(orgId: OrgId, rename: OrganizationRename): Promise<Organization>;
    /** @param confirmSlug sent as typed: the backend compares it with the slug and is the authority. */
    delete(orgId: OrgId, confirmSlug: string): Promise<void>;
}
