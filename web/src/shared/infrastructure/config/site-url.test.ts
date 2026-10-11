import { describe, expect, it } from "vitest";
import { InvalidServerEnvError } from "./server-env";
import { siteUrl } from "./site-url";

describe("siteUrl", () => {
    it("uses the public base URL without its trailing slash", () => {
        expect(siteUrl({ PALLET_PUBLIC_BASE_URL: "https://pallet.dev/" }).href).toBe("https://pallet.dev/");
        expect(siteUrl({ PALLET_PUBLIC_BASE_URL: "https://pallet.dev/" }).origin).toBe("https://pallet.dev");
    });

    it.each([undefined, ""])("falls back to the local dev server when the value is %j", (value) => {
        expect(siteUrl({ PALLET_PUBLIC_BASE_URL: value }).origin).toBe("http://localhost:5173");
    });

    it("rejects a value that is not an http(s) URL, naming the variable", () => {
        expect(() => siteUrl({ PALLET_PUBLIC_BASE_URL: "ftp://pallet.dev" })).toThrow(InvalidServerEnvError);
        expect(() => siteUrl({ PALLET_PUBLIC_BASE_URL: "ftp://pallet.dev" })).toThrow(/PALLET_PUBLIC_BASE_URL/);
    });
});
