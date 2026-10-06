import { asIsoInstant } from "@/shared/domain/instant";
import { describe, expect, it } from "vitest";
import { asAccountSessionId } from "../domain/account-session";
import { makeListSessions } from "./list-sessions";
import { ScriptedAccountRepository } from "./testing/in-memory";

const session = (id: string, lastAccessedAt: string) => ({
    id: asAccountSessionId(id),
    ipAddress: "41.90.64.211",
    startedAt: asIsoInstant("2026-01-01T08:00:00Z"),
    lastAccessedAt: asIsoInstant(lastAccessedAt),
});

describe("listSessions", () => {
    it("lists the most recently active session first", async () => {
        const accounts = new ScriptedAccountRepository();
        accounts.sessions = [session("kc-old", "2026-01-09T10:00:00Z"), session("kc-new", "2026-01-15T11:00:00Z")];

        expect((await makeListSessions(accounts)()).map(({ id }) => id)).toEqual(["kc-new", "kc-old"]);
    });
});
