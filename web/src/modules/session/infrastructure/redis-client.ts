import "server-only";

import type { Logger } from "@/shared/infrastructure/logging/logger";
import { createClient, type RedisClientType } from "redis";

export type RedisConnection = () => Promise<RedisClientType>;

type ConnectionRegistry = Map<string, Promise<RedisClientType>>;

const REGISTRY_KEY = Symbol.for("pallet.web.redis");

function registry(): ConnectionRegistry {
    const holder = globalThis as unknown as Record<symbol, ConnectionRegistry | undefined>;
    const existing = holder[REGISTRY_KEY];
    if (existing !== undefined) return existing;
    const created: ConnectionRegistry = new Map();
    holder[REGISTRY_KEY] = created;
    return created;
}

/** One lazily opened client per URL per process, surviving dev-server module reloads. */
export function lazyRedisConnection(url: string, logger: Logger): RedisConnection {
    return () => {
        const open = registry();
        let connection = open.get(url);
        if (connection === undefined) {
            const client: RedisClientType = createClient({ url });
            client.on("error", (error: unknown) => {
                logger.error("redis.error", { error });
            });
            connection = client.connect().catch((error: unknown) => {
                open.delete(url);
                throw error;
            });
            open.set(url, connection);
        }
        return connection;
    };
}
