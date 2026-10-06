import { describe, expect, it } from "vitest";
import {
    MAX_ORGANIZATION_NAME_LENGTH,
    parseJoinedOrganization,
    parseSignInParams,
    parseSignInReason,
    parseVerified,
    signInPath,
} from "./sign-in-path";

describe("signInPath", () => {
    it("builds the bare sign-in path", () => {
        expect(signInPath()).toBe("/login");
    });

    it("encodes the way back and the reason", () => {
        expect(signInPath({ reason: "expired", next: "/orgs/o-1/apps?page=2" })).toBe(
            "/login?reason=expired&next=%2Forgs%2Fo-1%2Fapps%3Fpage%3D2",
        );
    });

    it("carries a confirmed email to prefill", () => {
        expect(signInPath({ verified: true, email: " ada+ops@example.com " })).toBe(
            "/login?verified=1&email=ada%2Bops%40example.com",
        );
    });

    it("marks a completed password reset", () => {
        expect(signInPath({ reset: true })).toBe("/login?reset=1");
    });

    it("names the organization an invite was accepted for", () => {
        expect(signInPath({ joined: " Kilima Labs " })).toBe("/login?joined=Kilima+Labs");
    });

    it("leaves out a blank email", () => {
        expect(signInPath({ email: " " })).toBe("/login");
    });
});

describe("parseSignInReason", () => {
    it.each([
        ["expired", "expired"],
        ["other", undefined],
        [["expired"], undefined],
        [undefined, undefined],
    ])("reads %j as %s", (value, reason) => {
        expect(parseSignInReason(value)).toBe(reason);
    });
});

describe("parseVerified", () => {
    it.each([
        ["1", true],
        ["true", false],
        [["1"], false],
        [undefined, false],
    ])("reads %j as %s", (value, verified) => {
        expect(parseVerified(value)).toBe(verified);
    });
});

describe("parseJoinedOrganization", () => {
    it.each([
        [" Kilima Labs ", "Kilima Labs"],
        ["", undefined],
        ["Kilima\u0000Labs", undefined],
        ["x".repeat(MAX_ORGANIZATION_NAME_LENGTH + 1), undefined],
        [["Kilima Labs"], undefined],
    ])("reads %j as %s", (value, name) => {
        expect(parseJoinedOrganization(value)).toBe(name);
    });
});

describe("parseSignInParams", () => {
    it("reads every parameter the page understands", () => {
        expect(
            parseSignInParams({
                next: "/orgs",
                reason: "expired",
                verified: "1",
                reset: "1",
                joined: "Kilima Labs",
                email: "ada@example.com",
            }),
        ).toEqual({
            next: "/orgs",
            reason: "expired",
            verified: true,
            reset: true,
            joined: "Kilima Labs",
            email: "ada@example.com",
        });
    });

    it("drops values that aren't what they claim to be", () => {
        expect(
            parseSignInParams({ next: ["/a", "/b"], email: "javascript:alert(1)", verified: "yes", reset: "true" }),
        ).toEqual({
            next: undefined,
            reason: undefined,
            verified: false,
            reset: false,
            joined: undefined,
            email: undefined,
        });
    });
});
