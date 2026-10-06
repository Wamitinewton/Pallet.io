import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";
import { config, proxy } from "./proxy";

const visit = (path: string, cookie?: string) =>
    proxy(new NextRequest(`http://localhost:5173${path}`, cookie === undefined ? {} : { headers: { Cookie: cookie } }));

describe("proxy route guard", () => {
    it("redirects a dashboard visit without a session cookie to sign-in, keeping the way back", () => {
        const response = visit("/orgs/o-1/apps?page=2");

        expect(response.status).toBe(307);
        const location = new URL(response.headers.get("Location") ?? "");
        expect(location.pathname).toBe("/login");
        expect(location.searchParams.get("next")).toBe("/orgs/o-1/apps?page=2");
    });

    it.each(["pallet_session=x", "__Host-pallet_session=x"])("lets a visit with %s through", (cookie) => {
        expect(visit("/account", cookie).headers.has("Location")).toBe(false);
    });

    it("hands the requested path to the layouts, replacing any the client sent", () => {
        const response = proxy(
            new NextRequest("http://localhost:5173/orgs/o-1/apps?page=2", {
                headers: { Cookie: "pallet_session=x", "X-Pallet-Requested-Path": "/forged" },
            }),
        );

        expect(response.headers.get("x-middleware-request-x-pallet-requested-path")).toBe("/orgs/o-1/apps?page=2");
    });

    it("never echoes GitHub's code and state into the way back", () => {
        const location = new URL(visit("/github/callback?code=c0de&state=st4te").headers.get("Location") ?? "");

        expect(location.searchParams.get("next")).toBe("/github/callback");
        expect(location.search).not.toContain("c0de");
        expect(location.search).not.toContain("st4te");
    });

    it("hands the GitHub callback to the page without its query", () => {
        const response = visit("/github/callback?code=c0de&state=st4te", "pallet_session=x");

        expect(response.headers.get("x-middleware-request-x-pallet-requested-path")).toBe("/github/callback");
    });

    it("guards only the signed-in paths", () => {
        expect(config.matcher).toEqual(["/orgs/:path*", "/account/:path*", "/notifications/:path*", "/github/:path*"]);
    });
});
