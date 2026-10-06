export const DEFAULT_SIGNED_IN_PATH = "/orgs";

const PROBE_ORIGIN = "http://pallet.invalid";
const RESERVED_PREFIXES = ["/api", "/bff"];

const UNSAFE_CHARACTER = /[\s\\]|\p{Cc}/u;

/**
 * `next` when it names a page on this site, else the default landing path. Browsers strip tabs and
 * newlines and read `\` as `/`, so either could turn a path into a protocol-relative URL.
 */
export function safeRedirect(next: string | null | undefined): string {
    if (next === null || next === undefined) return DEFAULT_SIGNED_IN_PATH;
    if (!next.startsWith("/") || next.startsWith("//")) return DEFAULT_SIGNED_IN_PATH;
    if (UNSAFE_CHARACTER.test(next)) return DEFAULT_SIGNED_IN_PATH;

    let url: URL;
    try {
        url = new URL(next, PROBE_ORIGIN);
    } catch {
        return DEFAULT_SIGNED_IN_PATH;
    }
    if (url.origin !== PROBE_ORIGIN) return DEFAULT_SIGNED_IN_PATH;

    const path = url.pathname.toLowerCase();
    if (RESERVED_PREFIXES.some((prefix) => path === prefix || path.startsWith(`${prefix}/`))) {
        return DEFAULT_SIGNED_IN_PATH;
    }
    return `${url.pathname}${url.search}${url.hash}`;
}
