import { RedisContainer } from "@testcontainers/redis";
import type { TestProject } from "vitest/node";

export const REDIS_IMAGE = "redis:7-alpine";

declare module "vitest" {
    export interface ProvidedContext {
        redisUrl: string;
    }
}

export default async function startSharedRedis(project: TestProject): Promise<() => Promise<void>> {
    const container = await new RedisContainer(REDIS_IMAGE).start();
    project.provide("redisUrl", container.getConnectionUrl());
    return async () => {
        await container.stop();
    };
}
