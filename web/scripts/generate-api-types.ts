import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import openapiTS, { astToString, type OpenAPI3 } from "openapi-typescript";

const SERVICES = ["identity", "org-team", "git-integration", "notification"] as const;
type Service = (typeof SERVICES)[number];

const API_PREFIX = "/api/v1";
const root = resolve(import.meta.dirname, "..");
const specDir = resolve(root, "openapi");
const typesDir = resolve(root, "src/shared/infrastructure/api/generated");
const gatewayUrl = (process.env.PALLET_GATEWAY_URL ?? "http://localhost:8083").replace(/\/+$/, "");

async function fetchSpec(service: Service): Promise<OpenAPI3> {
    const url = `${gatewayUrl}${API_PREFIX}/${service}/v3/api-docs`;
    const response = await fetch(url, { headers: { Accept: "application/json" } });
    if (!response.ok) {
        throw new Error(`GET ${url} answered ${String(response.status)}; is the stack running?`);
    }
    return (await response.json()) as OpenAPI3;
}

function canonical(value: unknown): unknown {
    if (Array.isArray(value)) return value.map(canonical);
    if (value !== null && typeof value === "object") {
        return Object.fromEntries(
            Object.entries(value)
                .sort(([a], [b]) => a.localeCompare(b))
                .map(([key, child]) => [key, canonical(child)]),
        );
    }
    return value;
}

// Clients are created per service with `<base>/<service>` as their base URL, so paths are keyed relative to it.
function relativeToService(spec: OpenAPI3, service: Service): OpenAPI3 {
    const prefix = `${API_PREFIX}/${service}`;
    const paths = Object.fromEntries(
        Object.entries(spec.paths ?? {}).map(([path, item]) => {
            if (!path.startsWith(`${prefix}/`)) {
                throw new Error(`${service}: path ${path} is outside ${prefix}`);
            }
            return [path.slice(prefix.length), item];
        }),
    );
    return { ...spec, paths };
}

async function generate(service: Service): Promise<void> {
    const spec = canonical(await fetchSpec(service)) as OpenAPI3;
    await writeFile(resolve(specDir, `${service}.json`), `${JSON.stringify(spec, null, 2)}\n`);

    const ast = await openapiTS(relativeToService(spec, service), { alphabetize: true });
    await writeFile(resolve(typesDir, `${service}.d.ts`), astToString(ast));
    console.log(`${service}: ${String(Object.keys(spec.paths ?? {}).length)} paths`);
}

await mkdir(specDir, { recursive: true });
await mkdir(typesDir, { recursive: true });
for (const service of SERVICES) {
    await generate(service);
}
