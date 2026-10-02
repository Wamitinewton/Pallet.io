import { describe, expect, it } from "vitest";
import { InvalidServerEnvError, parseServerEnv } from "./server-env";

const key = (bytes: number) => Buffer.alloc(bytes, 7).toString("base64");

const valid = {
    PALLET_GATEWAY_URL: "http://localhost:8083/",
    PALLET_PUBLIC_BASE_URL: "http://localhost:5173",
    PALLET_REDIS_URL: "redis://localhost:6379",
    PALLET_SESSION_ENCRYPTION_KEY: key(32),
    PALLET_TRUSTED_PROXY_HOPS: "1",
    LOG_LEVEL: "info",
    OTEL_SERVICE_NAME: "pallet-web",
};

function problemsOf(source: Record<string, string | undefined>): readonly string[] {
    try {
        parseServerEnv(source);
    } catch (error) {
        if (error instanceof InvalidServerEnvError) return error.problems;
        throw error;
    }
    throw new Error("expected the environment to be rejected");
}

describe("parseServerEnv", () => {
    it("parses a complete environment", () => {
        expect(parseServerEnv(valid)).toMatchObject({
            PALLET_GATEWAY_URL: "http://localhost:8083",
            PALLET_TRUSTED_PROXY_HOPS: 1,
            OTEL_EXPORTER_OTLP_ENDPOINT: undefined,
        });
    });

    it("fails naming a missing variable", () => {
        expect(problemsOf({ ...valid, PALLET_GATEWAY_URL: undefined })).toEqual(["PALLET_GATEWAY_URL is required"]);
    });

    it("treats a blank value as missing", () => {
        expect(problemsOf({ ...valid, OTEL_SERVICE_NAME: "" })).toEqual(["OTEL_SERVICE_NAME is required"]);
    });

    it("rejects a 31-byte encryption key", () => {
        expect(problemsOf({ ...valid, PALLET_SESSION_ENCRYPTION_KEY: key(31) })).toEqual([
            "PALLET_SESSION_ENCRYPTION_KEY must decode to 32 bytes",
        ]);
    });

    it("reports every problem at once and never echoes a value", () => {
        const secret = key(16);
        const error = (() => {
            try {
                parseServerEnv({ ...valid, PALLET_REDIS_URL: "http://cache", PALLET_SESSION_ENCRYPTION_KEY: secret });
            } catch (thrown) {
                return thrown as InvalidServerEnvError;
            }
            throw new Error("expected the environment to be rejected");
        })();

        expect(error.problems).toHaveLength(2);
        expect(error.message).toContain("PALLET_REDIS_URL");
        expect(error.message).not.toContain(secret);
    });
});
