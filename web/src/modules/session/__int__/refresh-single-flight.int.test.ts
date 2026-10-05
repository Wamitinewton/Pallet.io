import { buildSessionRuntime, type SessionRuntime } from "@/composition/session";
import { createLogger } from "@/shared/infrastructure/logging/logger";
import { fakeJwt } from "@/test/jwt";
import { error, ok } from "@/test/msw/envelopes";
import { gatewayUrl, server } from "@/test/msw/server";
import { connectTestRedis, type TestRedis } from "@/test/redis";
import { testServerEnv } from "@/test/session-env";
import { delay, http } from "msw";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { openSession, type Session } from "../domain/session";
import { issuedTokens, sessionId } from "../domain/testing/fixtures";

const CONCURRENT_REQUESTS = 10;

let redis: TestRedis;
let runtime: SessionRuntime;
let refreshCalls: string[];

const rotated = fakeJwt({ sub: issuedTokens().claims.subject, email: "ada@example.com", sid: "kc-1", jti: "rotated" });

function aboutToExpire(): Session {
    return openSession(
        sessionId("a"),
        issuedTokens({ accessToken: "access-old", refreshToken: "refresh-old", accessExpiresInSeconds: 10 }),
        "ada@example.com",
        new Date(),
    );
}

beforeAll(async () => {
    redis = await connectTestRedis();
    runtime = buildSessionRuntime(testServerEnv(redis.url), {
        redis: () => Promise.resolve(redis.client),
        logger: createLogger("fatal"),
    });
});

afterAll(() => {
    redis.close();
});

beforeEach(async () => {
    await redis.client.flushAll();
    refreshCalls = [];
});

describe("refresh single-flight", () => {
    it("spends the refresh token once for ten concurrent resolutions, and all ten get the new token", async () => {
        server.use(
            http.post(gatewayUrl("/identity/auth/refresh"), async ({ request }) => {
                const { refreshToken } = (await request.json()) as { refreshToken: string };
                refreshCalls.push(refreshToken);
                await delay(300);
                return ok({
                    accessToken: rotated,
                    expiresIn: 300,
                    refreshToken: "refresh-new",
                    refreshExpiresIn: 1800,
                });
            }),
        );
        await runtime.store.create(aboutToExpire());

        const results = await Promise.all(
            Array.from({ length: CONCURRENT_REQUESTS }, () => runtime.resolveSession(sessionId("a"))),
        );

        expect(refreshCalls).toEqual(["refresh-old"]);
        for (const result of results) {
            expect(result).toMatchObject({ status: "active", session: { accessToken: rotated } });
        }
        await expect(runtime.store.get(sessionId("a"))).resolves.toMatchObject({ refreshToken: "refresh-new" });
        expect(await redis.client.keys("web:session:*:refresh")).toEqual([]);
    });

    it("signs every concurrent request out once when identity rejects the refresh token", async () => {
        server.use(
            http.post(gatewayUrl("/identity/auth/refresh"), async ({ request }) => {
                refreshCalls.push(((await request.json()) as { refreshToken: string }).refreshToken);
                await delay(100);
                return error(401, "INVALID_TOKEN");
            }),
        );
        await runtime.store.create(aboutToExpire());

        const results = await Promise.all(
            Array.from({ length: CONCURRENT_REQUESTS }, () => runtime.resolveSession(sessionId("a"))),
        );

        expect(refreshCalls).toHaveLength(1);
        expect(results.every((result) => result.status === "expired")).toBe(true);
        await expect(runtime.store.get(sessionId("a"))).resolves.toBeUndefined();
    });
});
