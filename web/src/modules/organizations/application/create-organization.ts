import { newOrganizationSchema, type NewOrganizationForm, type Organization } from "../domain/organization";
import type { OrganizationRepository } from "./ports";

export type CreateOrganization = (organization: NewOrganizationForm) => Promise<Organization>;

export function makeCreateOrganization(organizations: OrganizationRepository): CreateOrganization {
    return async (organization) => organizations.create(newOrganizationSchema.parse(organization));
}
