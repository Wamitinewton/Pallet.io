import { CSRF_HEADER, CSRF_HEADER_VALUE } from "../api/headers";

export const CSRF_REJECTED_CODE = "CSRF_REJECTED";
export const CSRF_REJECTED_MESSAGE = "Cross-site request rejected";

export function originOf(baseUrl: string): string {
    return new URL(baseUrl).origin;
}

/**
 * True when a browser request demonstrably comes from `expectedOrigin`: its `Origin` matches, or, when
 * the browser omitted `Origin`, Fetch Metadata says `same-origin`.
 */
export function isSameOriginRequest(headers: Headers, expectedOrigin: string): boolean {
    const origin = headers.get("origin");
    if (origin !== null) return origin === expectedOrigin;
    return headers.get("sec-fetch-site") === "same-origin";
}

/** The CSRF gate for every state-changing browser request: our custom header and a same-origin source. */
export function passesCsrfCheck(headers: Headers, expectedOrigin: string): boolean {
    return headers.get(CSRF_HEADER) === CSRF_HEADER_VALUE && isSameOriginRequest(headers, expectedOrigin);
}
