import { SessionExpiredError } from "@/shared/domain/errors";
import { describe, expect, it } from "vitest";
import { sessionSummary } from "../domain/testing/fixtures";
import { makeReadSessionSummary } from "./read-session-summary";
import { ScriptedSessionGateway } from "./testing/in-memory";

describe("readSessionSummary", () => {
    it("reads this browser's session without any token", async () => {
        const summary = await makeReadSessionSummary(new ScriptedSessionGateway())();

        expect(summary).toEqual(sessionSummary());
        expect(summary).not.toHaveProperty("accessToken");
        expect(summary).not.toHaveProperty("refreshToken");
    });

    it("passes an ended session through", async () => {
        const gateway = new ScriptedSessionGateway();
        gateway.nextSummary = () => Promise.reject(new SessionExpiredError());

        await expect(makeReadSessionSummary(gateway)()).rejects.toBeInstanceOf(SessionExpiredError);
    });
});
