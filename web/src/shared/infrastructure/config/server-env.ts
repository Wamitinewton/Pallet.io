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

const serverEnvSchema = z.object({
    PALLET_GATEWAY_URL: httpUrl,
    PALLET_PUBLIC_BASE_URL: httpUrl,
    PALLET_REDIS_URL: z.url({ protocol: /^rediss?$/, error: "must be a redis:// or rediss:// URL" }),
    PALLET_SESSION_ENCRYPTION_KEY: base64Key,
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
