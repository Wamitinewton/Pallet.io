import { ApiError } from "@/shared/domain/errors";
import { describe, expect, it } from "vitest";
import { asAccountSessionId } from "../domain/account-session";
import { makeRevokeOtherSessions } from "./revoke-other-sessions";
import { makeRevokeSession } from "./revoke-session";
import { ScriptedAccountRepository } from "./testing/in-memory";

const LAPTOP = asAccountSessionId("kc-laptop");
const apiError = (status: number, code: string) => new ApiError(status, code, "No", [], {}, undefined);

describe("revokeSession", () => {
    it("ends the named session", async () => {
        const accounts = new ScriptedAccountRepository();

        await makeRevokeSession(accounts)(LAPTOP);

        expect(accounts.revoked).toEqual([LAPTOP]);
    });

    it("treats a session that already ended as ended", async () => {
        const accounts = new ScriptedAccountRepository();
        accounts.nextRevokeSession = () => Promise.reject(apiError(404, "SESSION_NOT_FOUND"));

        await expect(makeRevokeSession(accounts)(LAPTOP)).resolves.toBeUndefined();
    });

    it("passes any other failure through", async () => {
        const accounts = new ScriptedAccountRepository();
        const failure = apiError(503, "SERVICE_UNAVAILABLE");
        accounts.nextRevokeSession = () => Promise.reject(failure);

        await expect(makeRevokeSession(accounts)(LAPTOP)).rejects.toBe(failure);
    });
});

describe("revokeOtherSessions", () => {
    it("asks the backend to end every session but this one", async () => {
        const accounts = new ScriptedAccountRepository();

        await makeRevokeOtherSessions(accounts)();

        expect(accounts.otherSessionsRevoked).toBe(1);
    });
});
