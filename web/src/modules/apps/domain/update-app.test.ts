import { describe, expect, it } from "vitest";
import { anApp, PAYMENTS_TEAM_ID, WEB_TEAM_ID } from "./testing/fixtures";
import { appChange, appSettingsOf, appSettingsSchema, isNoChange, type AppSettingsForm } from "./update-app";

const checkout = anApp();
const change = (settings: AppSettingsForm, app = checkout) => appChange(app, appSettingsSchema.parse(settings));

describe("appChange", () => {
    it("is empty when nothing differs", () => {
        const unchanged = change({ name: " Checkout API ", teamId: PAYMENTS_TEAM_ID });

        expect(unchanged).toEqual({});
        expect(isNoChange(unchanged)).toBe(true);
    });

    it("carries the name alone when only the name changed, leaving teamId absent", () => {
        const renamed = change({ name: "Checkout", teamId: PAYMENTS_TEAM_ID });

        expect(renamed).toEqual({ name: "Checkout" });
        expect("teamId" in renamed).toBe(false);
    });

    it("detaches with an explicit null", () => {
        const detached = change({ name: "Checkout API", teamId: "" });

        expect(detached).toEqual({ teamId: null });
        expect(isNoChange(detached)).toBe(false);
    });

    it("moves to another team", () => {
        expect(change({ name: "Checkout API", teamId: WEB_TEAM_ID })).toEqual({ teamId: WEB_TEAM_ID });
    });

    it("assigns an app that had no team", () => {
        expect(change({ name: "Checkout API", teamId: WEB_TEAM_ID }, anApp({ teamId: null }))).toEqual({
            teamId: WEB_TEAM_ID,
        });
    });

    it("sends nothing for an app with no team left without one", () => {
        expect(change({ name: "Checkout API", teamId: "" }, anApp({ teamId: null }))).toEqual({});
    });

    it("carries both when both changed", () => {
        expect(change({ name: "Checkout", teamId: "" })).toEqual({ name: "Checkout", teamId: null });
    });
});

describe("appSettingsSchema", () => {
    it.each([
        ["a blank name", "   ", "Enter a name for the app"],
        ["a name over 100 characters", "a".repeat(101), "Keep the name to 100 characters or fewer"],
    ])("refuses %s", (_, name, message) => {
        expect(appSettingsSchema.safeParse({ name, teamId: "" }).error?.issues[0]?.message).toBe(message);
    });
});

describe("appSettingsOf", () => {
    it("reads no team as the empty choice", () => {
        expect(appSettingsOf(anApp({ teamId: null }))).toEqual({ name: "Checkout API", teamId: "" });
        expect(appSettingsOf(checkout)).toEqual({ name: "Checkout API", teamId: PAYMENTS_TEAM_ID });
    });
});
