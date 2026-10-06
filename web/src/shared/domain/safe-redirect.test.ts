import { describe, expect, it } from "vitest";
import { DEFAULT_SIGNED_IN_PATH, safeRedirect } from "./safe-redirect";

describe("safeRedirect", () => {
    it.each(["/orgs/x", "/orgs/o-1/apps?status=active&page=2", "/account#sessions", "/notifications"])(
        "follows the internal page %s",
        (next) => {
            expect(safeRedirect(next)).toBe(next);
        },
    );

    it("normalizes dot segments before checking the path", () => {
        expect(safeRedirect("/orgs/../account")).toBe("/account");
    });

    it.each([
        ["protocol-relative", "//evil.com"],
        ["backslash", "/\\evil.com"],
        ["leading backslash", "\\\\evil.com"],
        ["absolute URL", "https://x"],
        ["scheme", "javascript:alert(1)"],
        ["relative path", "orgs"],
        ["tab smuggling", "/\t/evil.com"],
        ["newline smuggling", "/\n/evil.com"],
        ["the session API", "/api/session"],
        ["the session API root", "/api"],
        ["the proxy", "/bff/x"],
        ["the proxy in another case", "/BFF/identity/users/me"],
        ["the API through a dot segment", "/orgs/../api/session/logout"],
        ["empty", ""],
    ])("rejects %s", (_, next) => {
        expect(safeRedirect(next)).toBe(DEFAULT_SIGNED_IN_PATH);
    });

    it("keeps paths that only start like a reserved prefix", () => {
        expect(safeRedirect("/apis")).toBe("/apis");
    });

    it.each([null, undefined])("falls back to %s", (next) => {
        expect(safeRedirect(next)).toBe("/orgs");
    });
});
