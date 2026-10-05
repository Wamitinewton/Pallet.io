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

    it("guards only the dashboard paths", () => {
        expect(config.matcher).toEqual(["/orgs/:path*", "/account/:path*", "/notifications/:path*"]);
    });
});
