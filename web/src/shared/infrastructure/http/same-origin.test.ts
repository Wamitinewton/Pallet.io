import { describe, expect, it } from "vitest";
import { isSameOriginRequest, originOf, passesCsrfCheck } from "./same-origin";

const ORIGIN = "http://localhost:5173";

describe("isSameOriginRequest", () => {
    it("accepts a matching Origin", () => {
        expect(isSameOriginRequest(new Headers({ Origin: ORIGIN }), ORIGIN)).toBe(true);
    });

    it.each(["https://evil.example", "http://localhost:5174", "https://localhost:5173", "null"])(
        "rejects the Origin %s",
        (origin) => {
            expect(isSameOriginRequest(new Headers({ Origin: origin, "Sec-Fetch-Site": "same-origin" }), ORIGIN)).toBe(
                false,
            );
        },
    );

    it.each([
        ["same-origin", true],
        ["same-site", false],
        ["cross-site", false],
        ["none", false],
    ])("without Origin, Sec-Fetch-Site %s is %s", (site, expected) => {
        expect(isSameOriginRequest(new Headers({ "Sec-Fetch-Site": site }), ORIGIN)).toBe(expected);
    });

    it("rejects a request with neither header", () => {
        expect(isSameOriginRequest(new Headers(), ORIGIN)).toBe(false);
    });
});

describe("originOf", () => {
    it("drops path and trailing slash", () => {
        expect(originOf("https://app.pallet.dev/base/")).toBe("https://app.pallet.dev");
    });
});

describe("passesCsrfCheck", () => {
    it("needs both the custom header and a same-origin source", () => {
        expect(passesCsrfCheck(new Headers({ Origin: ORIGIN, "X-Pallet-Request": "1" }), ORIGIN)).toBe(true);
        expect(passesCsrfCheck(new Headers({ Origin: ORIGIN }), ORIGIN)).toBe(false);
        expect(passesCsrfCheck(new Headers({ Origin: "https://evil.example", "X-Pallet-Request": "1" }), ORIGIN)).toBe(
            false,
        );
    });
});
