import { asOrgId } from "@/shared/domain/ids";
import { describe, expect, it } from "vitest";
import { CHECKOUT_APP_ID } from "../domain/testing/fixtures";
import { makeRequestManualBuild } from "./request-manual-build";
import { InMemoryRepoLinkRepository } from "./testing/in-memory";

const orgId = asOrgId("org-kilima");
const command = { orgId, appId: CHECKOUT_APP_ID, productionBranch: "main" };

function setUp() {
    const links = new InMemoryRepoLinkRepository();
    return { links, requestManualBuild: makeRequestManualBuild(links) };
}

describe("requestManualBuild", () => {
    it("passes the intent's key through untouched", async () => {
        const { links, requestManualBuild } = setUp();

        await requestManualBuild({ ...command, form: { branch: "" }, idempotencyKey: "intent-1" });
        await requestManualBuild({ ...command, form: { branch: "" }, idempotencyKey: "intent-1" });

        expect(links.builds.map(({ idempotencyKey }) => idempotencyKey)).toEqual(["intent-1", "intent-1"]);
    });

    it("builds the production branch by naming none", async () => {
        const { links, requestManualBuild } = setUp();

        const build = await requestManualBuild({ ...command, form: { branch: " main " }, idempotencyKey: "k" });

        expect(links.builds[0]?.request).toEqual({});
        expect(build).toMatchObject({ branch: "main", eventId: "event-k" });
    });

    it("names another branch", async () => {
        const { links, requestManualBuild } = setUp();

        await requestManualBuild({ ...command, form: { branch: "release/1.2" }, idempotencyKey: "k" });

        expect(links.builds[0]?.request).toEqual({ branch: "release/1.2" });
    });

    it("refuses a branch Git wouldn't accept before asking for anything", async () => {
        const { links, requestManualBuild } = setUp();

        await expect(
            requestManualBuild({ ...command, form: { branch: "a..b" }, idempotencyKey: "k" }),
        ).rejects.toThrow();
        expect(links.builds).toEqual([]);
    });
});
