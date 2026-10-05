import { describe, expect, it } from "vitest";
import { sessionCookie } from "./session-cookie";

describe("sessionCookie", () => {
    it("uses the __Host- prefix and Secure on https", () => {
        const cookie = sessionCookie("https://app.pallet.dev");

        expect(cookie.name).toBe("__Host-pallet_session");
        expect(cookie.issue("abc", 1800)).toBe(
            "__Host-pallet_session=abc; Path=/; HttpOnly; SameSite=Lax; Secure; Max-Age=1800",
        );
    });

    it("drops the prefix and Secure on local http", () => {
        const cookie = sessionCookie("http://localhost:5173");

        expect(cookie.name).toBe("pallet_session");
        expect(cookie.issue("abc", 59.9)).toBe("pallet_session=abc; Path=/; HttpOnly; SameSite=Lax; Max-Age=59");
        expect(cookie.clear()).toBe("pallet_session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
    });

    it("reads its own cookie and nothing else", () => {
        const cookie = sessionCookie("http://localhost:5173");

        expect(cookie.read("theme=dark; pallet_session=abc ; other=1")).toBe("abc");
        expect(cookie.read("xpallet_session=abc")).toBeUndefined();
        expect(cookie.read(null)).toBeUndefined();
    });
});
