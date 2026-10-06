/** Picks a landing page only; the open organization is always the URL's. */
export const LAST_ORGANIZATION_COOKIE = "pallet_last_org";

const ONE_YEAR_SECONDS = 365 * 24 * 60 * 60;

function attributes(maxAgeSeconds: number, secure: boolean): string[] {
    return [`Max-Age=${String(maxAgeSeconds)}`, "Path=/", "SameSite=Lax", ...(secure ? ["Secure"] : [])];
}

export function lastOrganizationCookie(orgId: string, secure: boolean): string {
    return [`${LAST_ORGANIZATION_COOKIE}=${encodeURIComponent(orgId)}`, ...attributes(ONE_YEAR_SECONDS, secure)].join(
        "; ",
    );
}

export function clearedLastOrganizationCookie(secure: boolean): string {
    return [`${LAST_ORGANIZATION_COOKIE}=`, ...attributes(0, secure)].join("; ");
}

/** The remembered organization in a `document.cookie` string, if any. */
export function rememberedOrganization(cookies: string): string | undefined {
    for (const pair of cookies.split(";")) {
        const [name, ...value] = pair.trim().split("=");
        if (name !== LAST_ORGANIZATION_COOKIE) continue;
        try {
            return decodeURIComponent(value.join("="));
        } catch {
            return undefined;
        }
    }
    return undefined;
}

/** Stops a deleted organization from being the landing page, leaving any other remembered one alone. */
export function forgetLastOrganization(orgId: string): void {
    if (rememberedOrganization(document.cookie) !== orgId) return;
    document.cookie = clearedLastOrganizationCookie(window.location.protocol === "https:");
}
