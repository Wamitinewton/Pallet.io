import { createClient, type RedisClientType } from "redis";
import { inject } from "vitest";

export interface TestRedis {
    readonly url: string;
    readonly client: RedisClientType;
    close(): void;
}

/** A client on the run's shared Redis container, started once by `redis-global-setup.ts`. */
export async function connectTestRedis(): Promise<TestRedis> {
    const url = inject("redisUrl");
    const client: RedisClientType = createClient({ url });
    await client.connect();
    return {
        url,
        client,
        close() {
            client.destroy();
        },
    };
}
