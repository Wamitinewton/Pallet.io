import type { OrgId } from "@/shared/domain/ids";
import { confirmsDeletion, DeletionNotConfirmedError } from "../domain/delete-confirmation";
import type { OrganizationRepository } from "./ports";

export interface DeleteOrganizationCommand {
    readonly orgId: OrgId;
    readonly slug: string;
    readonly confirmation: string;
}

export type DeleteOrganization = (command: DeleteOrganizationCommand) => Promise<void>;

export function makeDeleteOrganization(organizations: OrganizationRepository): DeleteOrganization {
    return async ({ orgId, slug, confirmation }) => {
        if (!confirmsDeletion(slug, confirmation)) throw new DeletionNotConfirmedError();
        await organizations.delete(orgId, confirmation);
    };
}
