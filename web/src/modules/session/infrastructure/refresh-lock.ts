import { randomBytes } from "node:crypto";
import type { RefreshLock } from "../application/ports";
import type { RedisConnection } from "./redis-client";
import { refreshLockKey } from "./session-keys";

const RELEASE_IF_OWNER = `
if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
return 0
`;

export interface RedisRefreshLockOptions {
    readonly redis: RedisConnection;
    readonly leaseMs?: number;
}

export function redisRefreshLock({ redis, leaseMs = 10_000 }: RedisRefreshLockOptions): RefreshLock {
    return {
        async acquire(id) {
            const key = refreshLockKey(id);
            const owner = randomBytes(16).toString("base64url");
            const client = await redis();
            const acquired = await client.set(key, owner, {
                condition: "NX",
                expiration: { type: "PX", value: leaseMs },
            });
            if (acquired !== "OK") return undefined;

            return {
                async release() {
                    await client.eval(RELEASE_IF_OWNER, { keys: [key], arguments: [owner] });
                },
            };
        },
    };
}
