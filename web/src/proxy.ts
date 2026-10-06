import { SESSION_COOKIE_NAMES } from "@/modules/session/infrastructure/session-cookie";
import { REQUESTED_PATH_HEADER } from "@/shared/infrastructure/http/requested-path";
import { NextResponse, type NextRequest } from "next/server";

/** Pages whose query carries one-time secrets, which must never be echoed into a `next` parameter. */
const SECRET_QUERY_PATHS: ReadonlySet<string> = new Set(["/github/callback"]);

/** Optimistic: a missing cookie redirects early; the dashboard layout resolves the session for real. */
export function proxy(request: NextRequest): NextResponse {
    const { pathname, search } = request.nextUrl;
    const requestedPath = SECRET_QUERY_PATHS.has(pathname) ? pathname : `${pathname}${search}`;

    if (SESSION_COOKIE_NAMES.some((name) => request.cookies.has(name))) {
        const headers = new Headers(request.headers);
        headers.set(REQUESTED_PATH_HEADER, requestedPath);
        return NextResponse.next({ request: { headers } });
    }

    const login = request.nextUrl.clone();
    login.pathname = "/login";
    login.search = "";
    login.searchParams.set("next", requestedPath);
    return NextResponse.redirect(login);
}

export const config = {
    matcher: ["/orgs/:path*", "/account/:path*", "/notifications/:path*", "/github/:path*"],
};
