import { SESSION_COOKIE_NAMES } from "@/modules/session/infrastructure/session-cookie";
import { REQUESTED_PATH_HEADER } from "@/shared/infrastructure/http/requested-path";
import { NextResponse, type NextRequest } from "next/server";

/** Optimistic: a missing cookie redirects early; the dashboard layout resolves the session for real. */
export function proxy(request: NextRequest): NextResponse {
    const requestedPath = `${request.nextUrl.pathname}${request.nextUrl.search}`;

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
    matcher: ["/orgs/:path*", "/account/:path*", "/notifications/:path*"],
};
