import { asOrgId } from "@/shared/domain/ids";
import { describe, expect, it } from "vitest";
import { DeletionNotConfirmedError } from "../domain/delete-confirmation";
import { makeDeleteOrganization } from "./delete-organization";
import { InMemoryOrganizationRepository } from "./testing/in-memory";

const orgId = asOrgId("org-kilima");

describe("deleteOrganization", () => {
    it("sends the typed confirmation once it matches the slug", async () => {
        const organizations = new InMemoryOrganizationRepository();

        await makeDeleteOrganization(organizations)({ orgId, slug: "kilima-labs", confirmation: "kilima-labs" });

        expect(organizations.deletions).toEqual([{ orgId, confirmSlug: "kilima-labs" }]);
    });

    it("refuses a confirmation that doesn't match without asking the backend", async () => {
        const organizations = new InMemoryOrganizationRepository();

        await expect(
            makeDeleteOrganization(organizations)({ orgId, slug: "kilima-labs", confirmation: "Kilima-Labs" }),
        ).rejects.toBeInstanceOf(DeletionNotConfirmedError);
        expect(organizations.deletions).toEqual([]);
    });
});
