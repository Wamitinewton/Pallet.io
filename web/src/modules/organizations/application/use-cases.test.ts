import { asOrgId } from "@/shared/domain/ids";
import { describe, expect, it } from "vitest";
import { MY_ORGANIZATIONS_PAGE_SIZE } from "../domain/organization";
import { anOrganization, anOrgSummary } from "../domain/testing/fixtures";
import { InMemoryOrganizationRepository } from "./testing/in-memory";
import { makeOrganizationUseCases } from "./use-cases";

describe("organization use cases", () => {
    it("lists my organizations in one page as large as the backend allows", async () => {
        const organizations = new InMemoryOrganizationRepository([anOrgSummary()]);
        const { listMyOrganizations } = makeOrganizationUseCases({ organizations });

        const page = await listMyOrganizations();

        expect(page.items).toEqual([anOrgSummary()]);
        expect(organizations.listRequests).toEqual([{ page: 0, size: MY_ORGANIZATIONS_PAGE_SIZE }]);
    });

    it("reads a later page of my organizations at the same size", async () => {
        const organizations = new InMemoryOrganizationRepository([anOrgSummary()]);
        const { listMyOrganizations } = makeOrganizationUseCases({ organizations });

        await listMyOrganizations(2);

        expect(organizations.listRequests).toEqual([{ page: 2, size: MY_ORGANIZATIONS_PAGE_SIZE }]);
    });

    it("reads one organization by id", async () => {
        const organization = anOrganization({ orgId: asOrgId("org-2"), name: "Savanna Pay" });
        const { getOrganization } = makeOrganizationUseCases({
            organizations: new InMemoryOrganizationRepository([], [anOrganization(), organization]),
        });

        expect(await getOrganization(asOrgId("org-2"))).toEqual(organization);
    });
});
