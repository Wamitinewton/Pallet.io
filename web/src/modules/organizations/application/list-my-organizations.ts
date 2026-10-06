import type { Page } from "@/shared/domain/page";
import { MY_ORGANIZATIONS_PAGE_SIZE, type OrgSummary } from "../domain/organization";
import type { OrganizationRepository } from "./ports";

export type ListMyOrganizations = (page?: number) => Promise<Page<OrgSummary>>;

export function makeListMyOrganizations(organizations: OrganizationRepository): ListMyOrganizations {
    return (page = 0) => organizations.listMine({ page, size: MY_ORGANIZATIONS_PAGE_SIZE });
}
