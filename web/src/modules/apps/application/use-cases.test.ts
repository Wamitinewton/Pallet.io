import { ApiError } from "@/shared/domain/errors";
import { asAppId, asOrgId } from "@/shared/domain/ids";
import { describe, expect, it } from "vitest";
import { AppDeletionNotConfirmedError } from "../domain/app";
import { appListQuery, DEFAULT_APP_LIST_PARAMS } from "../domain/app-list-query";
import { anApp, PAYMENTS_TEAM_ID, WEB_TEAM_ID } from "../domain/testing/fixtures";
import { InMemoryAppRepository } from "./testing/in-memory";
import { makeAppUseCases } from "./use-cases";

const orgId = asOrgId("org-kilima");
const checkout = anApp();
const docs = anApp({
    id: asAppId("0d0c1a2b-3c4d-4e5f-8a6b-000000000001"),
    name: "Docs",
    slug: "docs",
    cloudProvider: "GCP",
    region: "africa-south1",
    teamId: null,
});

function setUp() {
    const apps = new InMemoryAppRepository([checkout, docs]);
    return { apps, useCases: makeAppUseCases({ apps }) };
}

describe("app use cases", () => {
    it("lists one page of apps for the query it is given", async () => {
        const { useCases } = setUp();

        const page = await useCases.listApps(orgId, appListQuery({ ...DEFAULT_APP_LIST_PARAMS, cloud: "GCP" }));

        expect(page.items).toEqual([docs]);
    });

    it("creates an app from the trimmed form, leaving an empty slug and team out", async () => {
        const { apps, useCases } = setUp();

        await useCases.createApp(orgId, {
            name: " Payments API ",
            slug: "",
            cloudProvider: "AWS",
            region: "eu-west-1",
            teamId: "",
        });

        expect(apps.created).toEqual([
            {
                orgId,
                app: { name: "Payments API", slug: undefined, cloudProvider: "AWS", region: "eu-west-1", teamId: null },
            },
        ]);
    });

    it("refuses a region the provider doesn't offer before asking the backend", async () => {
        const { apps, useCases } = setUp();

        await expect(
            useCases.createApp(orgId, {
                name: "Payments API",
                slug: "",
                cloudProvider: "GCP",
                region: "eu-west-1",
                teamId: "",
            }),
        ).rejects.toThrow();
        expect(apps.created).toEqual([]);
    });

    it("sends only the name when only the name changed", async () => {
        const { apps, useCases } = setUp();

        const outcome = await useCases.updateApp({
            orgId,
            app: checkout,
            settings: { name: "Checkout", teamId: PAYMENTS_TEAM_ID },
        });

        expect(outcome).toMatchObject({ status: "updated", app: { name: "Checkout" } });
        expect(apps.updates).toEqual([{ orgId, appId: checkout.id, change: { name: "Checkout" } }]);
    });

    it("detaches the app from its team with an explicit null", async () => {
        const { apps, useCases } = setUp();

        const outcome = await useCases.updateApp({
            orgId,
            app: checkout,
            settings: { name: "Checkout API", teamId: "" },
        });

        expect(outcome).toMatchObject({ status: "updated", app: { teamId: null } });
        expect(apps.updates.map(({ change }) => change)).toEqual([{ teamId: null }]);
    });

    it("moves the app to another team", async () => {
        const { apps, useCases } = setUp();

        await useCases.updateApp({ orgId, app: checkout, settings: { name: "Checkout API", teamId: WEB_TEAM_ID } });

        expect(apps.updates.map(({ change }) => change)).toEqual([{ teamId: WEB_TEAM_ID }]);
    });

    it("sends nothing when the settings are the ones the app already has", async () => {
        const { apps, useCases } = setUp();

        expect(
            await useCases.updateApp({
                orgId,
                app: checkout,
                settings: { name: " Checkout API ", teamId: PAYMENTS_TEAM_ID },
            }),
        ).toEqual({ status: "updated", app: checkout });
        expect(apps.updates).toEqual([]);
    });

    it("reads the app again on CONCURRENT_MODIFICATION and answers with it", async () => {
        const { apps, useCases } = setUp();
        apps.failNext = new ApiError(409, "CONCURRENT_MODIFICATION", "Conflict", [], {}, undefined);

        expect(await useCases.updateApp({ orgId, app: checkout, settings: { name: "Checkout", teamId: "" } })).toEqual({
            status: "changedMeanwhile",
            latest: checkout,
        });
    });

    it("lets any other refusal of an update through", async () => {
        const { apps, useCases } = setUp();
        apps.failNext = new ApiError(404, "TEAM_NOT_FOUND", "Team not found", [], {}, undefined);

        await expect(
            useCases.updateApp({ orgId, app: checkout, settings: { name: "Checkout API", teamId: WEB_TEAM_ID } }),
        ).rejects.toMatchObject({ code: "TEAM_NOT_FOUND" });
    });

    it("deletes an app once its slug is typed exactly", async () => {
        const { apps, useCases } = setUp();

        await useCases.deleteApp({ orgId, app: checkout, confirmation: "checkout-api" });

        expect(apps.deletions).toEqual([{ orgId, appId: checkout.id }]);
    });

    it("never deletes on a confirmation that doesn't match", async () => {
        const { apps, useCases } = setUp();

        await expect(useCases.deleteApp({ orgId, app: checkout, confirmation: "Checkout API" })).rejects.toBeInstanceOf(
            AppDeletionNotConfirmedError,
        );
        expect(apps.deletions).toEqual([]);
    });
});
