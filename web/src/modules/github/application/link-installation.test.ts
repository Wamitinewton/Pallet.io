import { ApiError } from "@/shared/domain/errors";
import { asOrgId } from "@/shared/domain/ids";
import { describe, expect, it } from "vitest";
import { INSTALLATION_NOT_ACCESSIBLE } from "../domain/github-errors";
import { AMANI_INSTALLATION, anInstallationLink, KILIMA_LABS_INSTALLATION } from "../domain/testing/fixtures";
import { makeLinkInstallation } from "./link-installation";
import { InMemoryInstallationRepository } from "./testing/in-memory";

const orgId = asOrgId("org-kilima");

describe("linkInstallation", () => {
    it("links an existing installation by id alone", async () => {
        const installations = new InMemoryInstallationRepository();

        const outcome = await makeLinkInstallation(installations)(orgId, { installationId: AMANI_INSTALLATION });

        expect(outcome).toMatchObject({ alreadyLinked: false, link: { installationId: AMANI_INSTALLATION } });
        expect(installations.links).toEqual([{ orgId, request: { installationId: AMANI_INSTALLATION } }]);
    });

    it("passes a fresh install's grant along with its id", async () => {
        const installations = new InMemoryInstallationRepository();
        const grant = { code: "c", state: "s" };

        await makeLinkInstallation(installations)(orgId, { installationId: AMANI_INSTALLATION, grant });

        expect(installations.links[0]?.request).toEqual({ installationId: AMANI_INSTALLATION, grant });
    });

    it("succeeds when the organization already had it, saying so", async () => {
        const installations = new InMemoryInstallationRepository([anInstallationLink()]);

        expect(
            await makeLinkInstallation(installations)(orgId, { installationId: KILIMA_LABS_INSTALLATION }),
        ).toMatchObject({ alreadyLinked: true });
    });

    it("leaves deciding who may link what to the service", async () => {
        const installations = new InMemoryInstallationRepository();
        installations.failNext = new ApiError(403, INSTALLATION_NOT_ACCESSIBLE, "No", [], {}, undefined);

        await expect(
            makeLinkInstallation(installations)(orgId, { installationId: AMANI_INSTALLATION }),
        ).rejects.toMatchObject({ code: INSTALLATION_NOT_ACCESSIBLE });
    });
});
