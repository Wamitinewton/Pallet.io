import "server-only";

import { z } from "zod";

const ENCRYPTION_KEY_BYTES = 32;

const httpUrl = z
    .url({ protocol: /^https?$/, error: "must be an http(s) URL" })
    .transform((url) => url.replace(/\/+$/, ""));

const base64Key = z
    .string()
    .regex(/^[A-Za-z0-9+/]+={0,2}$/, "must be base64")
    .refine((value) => Buffer.from(value, "base64").length === ENCRYPTION_KEY_BYTES, {
        error: `must decode to ${String(ENCRYPTION_KEY_BYTES)} bytes`,
    });

const keyId = z.string().regex(/^[a-z0-9]{1,16}$/, "must be 1-16 lowercase letters or digits");

const retiredKeys = z
    .string()
    .transform((value, context) => {
        const keys: Record<string, string> = {};
        for (const entry of value.split(",").map((part) => part.trim())) {
            const separator = entry.indexOf(":");
            const id = keyId.safeParse(entry.slice(0, separator));
            const key = base64Key.safeParse(entry.slice(separator + 1));
            if (separator < 1 || !id.success || !key.success) {
                context.addIssue({
                    code: "custom",
                    message: "must be a comma-separated list of <id>:<32-byte base64 key>",
                });
                return z.NEVER;
            }
            keys[id.data] = key.data;
        }
        return keys;
    })
    .optional();

const serverEnvSchema = z.object({
    PALLET_GATEWAY_URL: httpUrl,
    PALLET_PUBLIC_BASE_URL: httpUrl,
    PALLET_REDIS_URL: z.url({ protocol: /^rediss?$/, error: "must be a redis:// or rediss:// URL" }),
    PALLET_SESSION_ENCRYPTION_KEY: base64Key,
    PALLET_SESSION_ENCRYPTION_KEY_ID: keyId.default("1"),
    PALLET_SESSION_RETIRED_ENCRYPTION_KEYS: retiredKeys,
    PALLET_TRUSTED_PROXY_HOPS: z.coerce.number().int().min(0).max(10),
    LOG_LEVEL: z.enum(["fatal", "error", "warn", "info", "debug", "trace"]),
    OTEL_SERVICE_NAME: z.string().min(1),
    OTEL_EXPORTER_OTLP_ENDPOINT: httpUrl.optional(),
});

export type ServerEnv = Readonly<z.output<typeof serverEnvSchema>>;

export class InvalidServerEnvError extends Error {
    override readonly name = "InvalidServerEnvError";

    constructor(readonly problems: readonly string[]) {
        super(`Invalid server environment:\n${problems.map((problem) => `  - ${problem}`).join("\n")}`);
    }
}

export function parseServerEnv(source: Readonly<Record<string, string | undefined>>): ServerEnv {
    const blankAsMissing = Object.fromEntries(
        Object.keys(serverEnvSchema.shape).map((name) => [name, source[name] === "" ? undefined : source[name]]),
    );
    const result = serverEnvSchema.safeParse(blankAsMissing);
    if (result.success) return Object.freeze(result.data);

    throw new InvalidServerEnvError(
        result.error.issues.map((issue) => {
            const name = String(issue.path[0]);
            return blankAsMissing[name] === undefined ? `${name} is required` : `${name} ${issue.message}`;
        }),
    );
}

let cached: ServerEnv | undefined;

export function serverEnv(): ServerEnv {
    cached ??= parseServerEnv(process.env);
    return cached;
}
