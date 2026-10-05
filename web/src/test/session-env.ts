import { parseServerEnv, type ServerEnv } from "@/shared/infrastructure/config/server-env";
import { TEST_GATEWAY_URL, TEST_ORIGIN } from "./msw/server";

export const TEST_ENCRYPTION_KEY = Buffer.alloc(32, 7).toString("base64");

export function testServerEnvVars(redisUrl: string): Record<string, string> {
    return {
        PALLET_GATEWAY_URL: TEST_GATEWAY_URL,
        PALLET_PUBLIC_BASE_URL: TEST_ORIGIN,
        PALLET_REDIS_URL: redisUrl,
        PALLET_SESSION_ENCRYPTION_KEY: TEST_ENCRYPTION_KEY,
        PALLET_TRUSTED_PROXY_HOPS: "1",
        LOG_LEVEL: "fatal",
        OTEL_SERVICE_NAME: "pallet-web-test",
    };
}

export function testServerEnv(redisUrl: string): ServerEnv {
    return parseServerEnv(testServerEnvVars(redisUrl));
}
