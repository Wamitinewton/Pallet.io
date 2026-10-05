import { asUserId } from "@/shared/domain/ids";
import { connectTestRedis, type TestRedis } from "@/test/redis";
import { createHash } from "node:crypto";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import type { SessionStore } from "../application/ports";
import { openSession, type Session } from "../domain/session";
import { issuedTokens, sessionId } from "../domain/testing/fixtures";
import { redisSessionStore } from "../infrastructure/redis-session-store";
import { createSessionCipher } from "../infrastructure/session-cipher";

const key = (fill: number) => Buffer.alloc(32, fill).toString("base64");
const REFRESH_LIFETIME_SECONDS = 1800;

let redis: TestRedis;
let store: SessionStore;

const freshSession = (fill = "a", overrides: Partial<Session> = {}): Session => ({
    ...openSession(
        sessionId(fill),
        issuedTokens({ refreshExpiresInSeconds: REFRESH_LIFETIME_SECONDS }),
        "ada@example.com",
        new Date(),
    ),
    ...overrides,
});

const storeWith = (cipher = createSessionCipher({ currentKeyId: "1", currentKey: key(1) })) =>
    redisSessionStore({ redis: () => Promise.resolve(redis.client), cipher });

beforeAll(async () => {
    redis = await connectTestRedis();
});

afterAll(() => {
    redis.close();
});

beforeEach(async () => {
    await redis.client.flushAll();
    store = storeWith();
});

describe("redisSessionStore", () => {
    it("creates and reads back a session", async () => {
        const session = freshSession();
        await store.create(session);

        await expect(store.get(session.id)).resolves.toEqual(session);
    });

    it("keys the session by the SHA-256 of its id, never the id itself", async () => {
        const session = freshSession();
        await store.create(session);

        const keys = await redis.client.keys("web:session:*");
        expect(keys).toEqual([`web:session:${createHash("sha256").update(session.id).digest("hex")}`]);
        expect(keys[0]).not.toContain(session.id);
    });

    it("stores only ciphertext", async () => {
        const session = freshSession("a", { accessToken: "access-plain", refreshToken: "refresh-plain" });
        await store.create(session);

        const [stored] = await redis.client.keys("web:session:*");
        const dump = JSON.stringify(await redis.client.hGetAll(stored ?? ""));
        for (const secret of ["access-plain", "refresh-plain", "ada@example.com", session.id, session.userId]) {
            expect(dump).not.toContain(secret);
        }
    });

    it("expires with the refresh token", async () => {
        await store.create(freshSession());

        const [stored] = await redis.client.keys("web:session:*");
        const ttl = await redis.client.pTTL(stored ?? "");
        expect(ttl).toBeGreaterThan((REFRESH_LIFETIME_SECONDS - 5) * 1000);
        expect(ttl).toBeLessThanOrEqual(REFRESH_LIFETIME_SECONDS * 1000);
    });

    it("replaces tokens on the same id and extends the expiry", async () => {
        const session = freshSession();
        await store.create(session);
        const next = {
            ...session,
            accessToken: "access-3",
            refreshToken: "refresh-3",
            refreshExpiresAt: session.refreshExpiresAt,
        };

        await expect(store.replaceTokens(next, session.refreshToken)).resolves.toBe(true);
        await expect(store.get(session.id)).resolves.toEqual(next);
    });

    it("refuses to replace tokens against a stale previous refresh token", async () => {
        const session = freshSession();
        await store.create(session);
        await store.replaceTokens({ ...session, refreshToken: "refresh-3" }, session.refreshToken);

        await expect(
            store.replaceTokens({ ...session, refreshToken: "refresh-4" }, session.refreshToken),
        ).resolves.toBe(false);
        await expect(store.get(session.id)).resolves.toMatchObject({ refreshToken: "refresh-3" });
    });

    it("refuses to replace tokens on a session that no longer exists", async () => {
        const session = freshSession();

        await expect(store.replaceTokens(session, session.refreshToken)).resolves.toBe(false);
        expect(await redis.client.keys("web:session:*")).toEqual([]);
    });

    it("treats an undecryptable session as missing and removes it", async () => {
        const session = freshSession();
        await storeWith(createSessionCipher({ currentKeyId: "9", currentKey: key(9) })).create(session);

        await expect(store.get(session.id)).resolves.toBeUndefined();
        expect(await redis.client.keys("web:session:*")).toEqual([]);
    });

    it("reads a session written under a retired key", async () => {
        const session = freshSession();
        await store.create(session);
        const rotated = storeWith(
            createSessionCipher({ currentKeyId: "2", currentKey: key(2), retiredKeys: { "1": key(1) } }),
        );

        await expect(rotated.get(session.id)).resolves.toEqual(session);
    });

    it("deletes", async () => {
        const session = freshSession();
        await store.create(session);
        await store.delete(session.id);

        await expect(store.get(session.id)).resolves.toBeUndefined();
    });

    it("keeps the user id intact through the round trip", async () => {
        const session = freshSession("c", { userId: asUserId("4b1d9a52-0000-4000-8000-00000000abcd") });
        await store.create(session);

        await expect(store.get(session.id)).resolves.toMatchObject({ userId: "4b1d9a52-0000-4000-8000-00000000abcd" });
    });
});
