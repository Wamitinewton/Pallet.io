import { makeCreateOrganization, type CreateOrganization } from "./create-organization";
import { makeDeleteOrganization, type DeleteOrganization } from "./delete-organization";
import { makeGetOrganization, type GetOrganization } from "./get-organization";
import { makeListMyOrganizations, type ListMyOrganizations } from "./list-my-organizations";
import type { OrganizationRepository } from "./ports";
import { makeRenameOrganization, type RenameOrganization } from "./rename-organization";

export interface OrganizationUseCases {
    readonly listMyOrganizations: ListMyOrganizations;
    readonly getOrganization: GetOrganization;
    readonly createOrganization: CreateOrganization;
    readonly renameOrganization: RenameOrganization;
    readonly deleteOrganization: DeleteOrganization;
}

export interface OrganizationDependencies {
    readonly organizations: OrganizationRepository;
}

export function makeOrganizationUseCases({ organizations }: OrganizationDependencies): OrganizationUseCases {
    return {
        listMyOrganizations: makeListMyOrganizations(organizations),
        getOrganization: makeGetOrganization(organizations),
        createOrganization: makeCreateOrganization(organizations),
        renameOrganization: makeRenameOrganization(organizations),
        deleteOrganization: makeDeleteOrganization(organizations),
    };
}
