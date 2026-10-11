import { ApiError } from "@/shared/domain/errors";
import { asOrgId } from "@/shared/domain/ids";
import { describe, expect, it } from "vitest";
import { aPickerRepository, aRepoLink, CHECKOUT_APP_ID, KILIMA_LABS_INSTALLATION } from "../domain/testing/fixtures";
import { InMemoryRepoLinkRepository, InMemoryRepositoryPicker } from "./testing/in-memory";
import { makeRepoLinkUseCases } from "./use-cases";

const orgId = asOrgId("org-kilima");
const link = aRepoLink();
const conflict = () => new ApiError(409, "CONCURRENT_MODIFICATION", "Conflict", [], {}, undefined);

function setUp(links = new InMemoryRepoLinkRepository([link])) {
    const repositories = new InMemoryRepositoryPicker([
        aPickerRepository(),
        aPickerRepository({ fullName: "kilima-labs/web" }),
    ]);
    return { links, repositories, useCases: makeRepoLinkUseCases({ links, repositories }) };
}

describe("repository link use cases", () => {
    it("links the chosen repository with checked, normalized settings", async () => {
        const { links, useCases } = setUp(new InMemoryRepoLinkRepository([]));

        await useCases.linkRepository({
            orgId,
            appId: CHECKOUT_APP_ID,
            installationId: KILIMA_LABS_INSTALLATION,
            repository: aPickerRepository(),
            settings: { productionBranch: " main ", rootDirectory: "apps/web/", autoDeploy: false },
            deployNow: true,
        });

        expect(links.linked[0]?.request).toEqual({
            installationId: KILIMA_LABS_INSTALLATION,
            repoId: 72000001,
            settings: { productionBranch: "main", rootDirectory: "apps/web", autoDeploy: false },
            deployNow: true,
        });
    });

    it("never asks GitHub about a link whose settings break a rule", async () => {
        const { links, useCases } = setUp(new InMemoryRepoLinkRepository([]));

        await expect(
            useCases.linkRepository({
                orgId,
                appId: CHECKOUT_APP_ID,
                installationId: KILIMA_LABS_INSTALLATION,
                repository: aPickerRepository(),
                settings: { productionBranch: "main", rootDirectory: "../x", autoDeploy: true },
                deployNow: false,
            }),
        ).rejects.toThrow();
        expect(links.linked).toEqual([]);
    });

    it("reads no link as null", async () => {
        const { useCases } = setUp(new InMemoryRepoLinkRepository([]));

        expect(await useCases.getRepoLink(orgId, CHECKOUT_APP_ID)).toBeNull();
    });

    it("lists one page of the installation's repositories by name prefix", async () => {
        const { repositories, useCases } = setUp();

        const page = await useCases.listLinkableRepositories(orgId, KILIMA_LABS_INSTALLATION, {
            q: "we",
            page: 0,
            size: 20,
        });

        expect(page.items.map(({ fullName }) => fullName)).toEqual(["kilima-labs/web"]);
        expect(repositories.queries[0]?.installationId).toBe(KILIMA_LABS_INSTALLATION);
    });

    it("tunes only the fields that changed, with the version it read", async () => {
        const { links, useCases } = setUp();

        const outcome = await useCases.tuneRepoLink({
            orgId,
            link,
            settings: { productionBranch: "release", rootDirectory: "", autoDeploy: true },
        });

        expect(links.tunes).toEqual([{ change: { productionBranch: "release" }, version: 3 }]);
        expect(outcome).toMatchObject({ status: "updated", link: { productionBranch: "release", version: 4 } });
    });

    it("sends nothing when the settings are the ones the link has", async () => {
        const { links, useCases } = setUp();

        expect(
            await useCases.tuneRepoLink({
                orgId,
                link,
                settings: { productionBranch: "main", rootDirectory: "", autoDeploy: true },
            }),
        ).toEqual({ status: "updated", link });
        expect(links.tunes).toEqual([]);
    });

    it("reads the link again on CONCURRENT_MODIFICATION and answers with it", async () => {
        const { links, useCases } = setUp();
        const theirs = aRepoLink({ rootDirectory: "apps/web", version: 4 });
        links.replace(theirs);
        links.failNext = conflict();

        expect(
            await useCases.tuneRepoLink({
                orgId,
                link,
                settings: { productionBranch: "release", rootDirectory: "", autoDeploy: true },
            }),
        ).toEqual({ status: "changedMeanwhile", latest: theirs });
    });

    it("lets any other refusal of a change through", async () => {
        const { links, useCases } = setUp();
        links.failNext = new ApiError(422, "BRANCH_NOT_FOUND", "No such branch", [], {}, undefined);

        await expect(
            useCases.tuneRepoLink({
                orgId,
                link,
                settings: { productionBranch: "nope", rootDirectory: "", autoDeploy: true },
            }),
        ).rejects.toMatchObject({ code: "BRANCH_NOT_FOUND" });
    });

    it("disconnects, leaving a link that says so", async () => {
        const { useCases } = setUp();

        await useCases.disconnectRepoLink(orgId, CHECKOUT_APP_ID);

        expect((await useCases.getRepoLink(orgId, CHECKOUT_APP_ID))?.connection).toMatchObject({
            status: "DISCONNECTED",
            reason: "UNLINKED_BY_USER",
        });
    });
});
