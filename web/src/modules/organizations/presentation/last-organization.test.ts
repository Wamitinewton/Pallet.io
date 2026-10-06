import { describe, expect, it } from "vitest";
import { clearedLastOrganizationCookie, lastOrganizationCookie, rememberedOrganization } from "./last-organization";

describe("lastOrganizationCookie", () => {
    it("lasts a year, is sent on top-level navigations and is Secure on https", () => {
        expect(lastOrganizationCookie("org-1", true)).toBe(
            "pallet_last_org=org-1; Max-Age=31536000; Path=/; SameSite=Lax; Secure",
        );
        expect(lastOrganizationCookie("org-1", false)).not.toContain("Secure");
    });

    it("encodes the id", () => {
        expect(lastOrganizationCookie("a;b", false)).toMatch(/^pallet_last_org=a%3Bb;/);
    });

    it("expires at once when cleared, on the same path", () => {
        expect(clearedLastOrganizationCookie(false)).toBe("pallet_last_org=; Max-Age=0; Path=/; SameSite=Lax");
    });
});

describe("rememberedOrganization", () => {
    it("reads and decodes the remembered id among other cookies", () => {
        expect(rememberedOrganization("theme=dark; pallet_last_org=a%3Bb; other=1")).toBe("a;b");
    });

    it("is undefined without the cookie or with a malformed value", () => {
        expect(rememberedOrganization("theme=dark")).toBeUndefined();
        expect(rememberedOrganization("pallet_last_org=%E0%A4%A")).toBeUndefined();
    });
});
