import { describe, expect, it } from "vitest";
import { anOrganization } from "../domain/testing/fixtures";
import { makeRenameOrganization } from "./rename-organization";
import { InMemoryOrganizationRepository } from "./testing/in-memory";

const kilima = anOrganization();

describe("renameOrganization", () => {
    it("sends the trimmed name and returns the renamed organization", async () => {
        const organizations = new InMemoryOrganizationRepository([], [kilima]);

        const renamed = await makeRenameOrganization(organizations)(kilima.orgId, { name: " Kilima Cloud " });

        expect(organizations.renames).toEqual([{ orgId: kilima.orgId, rename: { name: "Kilima Cloud" } }]);
        expect(renamed).toEqual({ ...kilima, name: "Kilima Cloud" });
    });

    it("refuses a blank name without asking the backend", async () => {
        const organizations = new InMemoryOrganizationRepository([], [kilima]);

        await expect(makeRenameOrganization(organizations)(kilima.orgId, { name: " " })).rejects.toThrow();
        expect(organizations.renames).toEqual([]);
    });
});
