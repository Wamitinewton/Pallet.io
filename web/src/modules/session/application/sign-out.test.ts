import { NetworkError } from "@/shared/domain/errors";
import { describe, expect, it } from "vitest";
import { makeSignOut } from "./sign-out";
import { ScriptedSessionGateway } from "./testing/in-memory";

describe("signOut", () => {
    it("reports a confirmed sign-out", async () => {
        const gateway = new ScriptedSessionGateway();

        await expect(makeSignOut(gateway)()).resolves.toEqual({ confirmed: true });
        expect(gateway.signOuts).toBe(1);
    });

    it("resolves even when the request fails", async () => {
        const gateway = new ScriptedSessionGateway();
        gateway.nextSignOut = () => Promise.reject(new NetworkError());

        await expect(makeSignOut(gateway)()).resolves.toEqual({ confirmed: false });
    });
});
