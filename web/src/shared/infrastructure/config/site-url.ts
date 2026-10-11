import "server-only";

import { httpUrl, InvalidServerEnvError } from "./server-env";

const LOCAL_SITE_URL = "http://localhost:5173";

/**
 * `PALLET_PUBLIC_BASE_URL` on its own: public pages and their metadata render at build time, where the rest of
 * the server environment does not exist. Unset means a local build.
 */
export function siteUrl(source: Readonly<Record<string, string | undefined>> = process.env): URL {
    const value = source.PALLET_PUBLIC_BASE_URL;
    if (value === undefined || value === "") return new URL(LOCAL_SITE_URL);

    const parsed = httpUrl.safeParse(value);
    if (!parsed.success) {
        throw new InvalidServerEnvError([`PALLET_PUBLIC_BASE_URL ${parsed.error.issues[0]?.message ?? "is invalid"}`]);
    }
    return new URL(parsed.data);
}
