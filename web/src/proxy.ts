import { SESSION_COOKIE_NAMES } from "@/modules/session/infrastructure/session-cookie";
import { NextResponse, type NextRequest } from "next/server";

/** Optimistic: a missing cookie redirects early; the dashboard layout resolves the session for real. */
export function proxy(request: NextRequest): NextResponse {
    if (SESSION_COOKIE_NAMES.some((name) => request.cookies.has(name))) return NextResponse.next();

    const login = request.nextUrl.clone();
    login.pathname = "/login";
    login.search = "";
    login.searchParams.set("next", `${request.nextUrl.pathname}${request.nextUrl.search}`);
    return NextResponse.redirect(login);
}

export const config = {
    matcher: ["/orgs/:path*", "/account/:path*", "/notifications/:path*"],
};
