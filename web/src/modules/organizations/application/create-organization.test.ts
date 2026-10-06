import { describe, expect, it } from "vitest";
import { makeCreateOrganization } from "./create-organization";
import { InMemoryOrganizationRepository } from "./testing/in-memory";

describe("createOrganization", () => {
    it("sends the trimmed name with the chosen slug and returns the new organization", async () => {
        const organizations = new InMemoryOrganizationRepository();

        const created = await makeCreateOrganization(organizations)({ name: "  Savanna Pay ", slug: "savanna-pay" });

        expect(organizations.created).toEqual([{ name: "Savanna Pay", slug: "savanna-pay" }]);
        expect(created).toMatchObject({ name: "Savanna Pay", slug: "savanna-pay", kind: "TEAM" });
    });

    it("leaves the slug for the backend to derive when none is given", async () => {
        const organizations = new InMemoryOrganizationRepository();

        await makeCreateOrganization(organizations)({ name: "Savanna Pay", slug: "" });

        expect(organizations.created).toEqual([{ name: "Savanna Pay", slug: undefined }]);
    });

    it.each([
        ["a blank name", { name: "  ", slug: "" }],
        ["an invalid slug", { name: "Savanna Pay", slug: "Savanna Pay" }],
    ])("refuses %s without asking the backend", async (_, organization) => {
        const organizations = new InMemoryOrganizationRepository();

        await expect(makeCreateOrganization(organizations)(organization)).rejects.toThrow();
        expect(organizations.created).toEqual([]);
    });
});
