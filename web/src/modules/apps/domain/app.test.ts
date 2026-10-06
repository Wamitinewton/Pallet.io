import { describe, expect, it } from "vitest";
import { appIdFromParam, confirmsAppDeletion, newAppSchema, type NewAppForm } from "./app";
import { anApp, PAYMENTS_TEAM_ID } from "./testing/fixtures";

const form = (overrides: Partial<NewAppForm> = {}): NewAppForm => ({
    name: "Payments API",
    slug: "",
    cloudProvider: "AWS",
    region: "eu-west-1",
    teamId: "",
    ...overrides,
});

describe("newAppSchema", () => {
    it("trims the name and leaves an empty slug and team out", () => {
        expect(newAppSchema.parse(form({ name: "  Payments API " }))).toEqual({
            name: "Payments API",
            slug: undefined,
            cloudProvider: "AWS",
            region: "eu-west-1",
            teamId: null,
        });
    });

    it("carries a chosen slug and team", () => {
        expect(newAppSchema.parse(form({ slug: "payments", teamId: PAYMENTS_TEAM_ID }))).toMatchObject({
            slug: "payments",
            teamId: PAYMENTS_TEAM_ID,
        });
    });

    it.each([
        ["a blank name", { name: " " }, "name", "Enter a name for the app"],
        ["a name over 100 characters", { name: "a".repeat(101) }, "name", "Keep the name to 100 characters or fewer"],
        ["a slug that isn't a DNS label", { slug: "Payments API" }, "slug", undefined],
        ["no region", { region: "" }, "region", "Choose a region"],
        ["another provider's region", { region: "europe-west1" }, "region", "Choose a region from the list"],
    ])("refuses %s", (_, overrides, field, message) => {
        const issue = newAppSchema.safeParse(form(overrides)).error?.issues[0];

        expect(issue?.path).toEqual([field]);
        if (message !== undefined) expect(issue?.message).toBe(message);
    });

    it("refuses a provider it doesn't know", () => {
        expect(newAppSchema.safeParse(form({ cloudProvider: "AZURE" as "AWS" })).success).toBe(false);
    });
});

describe("confirmsAppDeletion", () => {
    it("accepts exactly the slug", () => {
        expect(confirmsAppDeletion(anApp(), "checkout-api")).toBe(true);
    });

    it.each([
        ["the name", "Checkout API"],
        ["a different case", "Checkout-API"],
        ["a trailing space", "checkout-api "],
        ["nothing", ""],
    ])("refuses %s", (_, typed) => {
        expect(confirmsAppDeletion(anApp(), typed)).toBe(false);
    });
});

describe("appIdFromParam", () => {
    it("accepts a UUID", () => {
        expect(appIdFromParam("6f1c2a9e-4b7d-4e21-9a0c-3d5b8e7f1a24")).toBe("6f1c2a9e-4b7d-4e21-9a0c-3d5b8e7f1a24");
    });

    it.each(["checkout-api", "6f1c2a9e", ""])("refuses %j", (value) => {
        expect(appIdFromParam(value)).toBeUndefined();
    });
});
