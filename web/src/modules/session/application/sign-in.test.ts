import { ApiError } from "@/shared/domain/errors";
import { describe, expect, it } from "vitest";
import { makeSignIn } from "./sign-in";
import { ScriptedSessionGateway } from "./testing/in-memory";

describe("signIn", () => {
    it("sends the normalized credentials", async () => {
        const gateway = new ScriptedSessionGateway();

        await makeSignIn(gateway)({ email: " ada@example.com ", password: " secret " });

        expect(gateway.signedIn).toEqual([{ email: "ada@example.com", password: " secret " }]);
    });

    it("surfaces the gateway's error untouched", async () => {
        const gateway = new ScriptedSessionGateway();
        const rejection = new ApiError(401, "INVALID_CREDENTIALS", "Wrong", [], {}, undefined);
        gateway.nextSignIn = () => Promise.reject(rejection);

        await expect(makeSignIn(gateway)({ email: "ada@example.com", password: "x" })).rejects.toBe(rejection);
    });

    it("refuses credentials that could never be valid without calling the gateway", async () => {
        const gateway = new ScriptedSessionGateway();

        await expect(makeSignIn(gateway)({ email: "not-an-email", password: "" })).rejects.toThrow();
        expect(gateway.signedIn).toEqual([]);
    });
});
