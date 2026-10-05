const SECURE_NAME = "__Host-pallet_session";
const PLAIN_NAME = "pallet_session";

export const SESSION_COOKIE_NAMES: readonly string[] = [SECURE_NAME, PLAIN_NAME];

export interface SessionCookie {
    readonly name: string;
    read(cookieHeader: string | null): string | undefined;
    issue(value: string, maxAgeSeconds: number): string;
    clear(): string;
}

export function sessionCookie(publicBaseUrl: string): SessionCookie {
    const secure = new URL(publicBaseUrl).protocol === "https:";
    const name = secure ? SECURE_NAME : PLAIN_NAME;
    const attributes = ["Path=/", "HttpOnly", "SameSite=Lax", ...(secure ? ["Secure"] : [])].join("; ");

    return {
        name,

        read(cookieHeader) {
            if (cookieHeader === null) return undefined;
            for (const pair of cookieHeader.split(";")) {
                const separator = pair.indexOf("=");
                if (separator > 0 && pair.slice(0, separator).trim() === name) {
                    return pair.slice(separator + 1).trim();
                }
            }
            return undefined;
        },

        issue(value, maxAgeSeconds) {
            return `${name}=${value}; ${attributes}; Max-Age=${String(Math.max(0, Math.floor(maxAgeSeconds)))}`;
        },

        clear() {
            return `${name}=; ${attributes}; Max-Age=0`;
        },
    };
}
