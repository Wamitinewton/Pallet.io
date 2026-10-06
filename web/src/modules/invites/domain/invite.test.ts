import { fixedClock } from "@/shared/domain/clock";
import { asIsoInstant } from "@/shared/domain/instant";
import { describe, expect, it } from "vitest";
import { canInviteAgain, canManageInvite, displayStatus, isActionable } from "./invite";
import { anInvite } from "./testing/fixtures";

const now = fixedClock("2026-01-15T12:00:00Z").now();
const expiresAt = asIsoInstant("2026-01-15T12:00:00Z");

describe("isActionable", () => {
    it("allows a pending invite that hasn't reached its expiry", () => {
        expect(isActionable(anInvite({ expiresAt: asIsoInstant("2026-01-15T12:00:01Z") }), now)).toBe(true);
    });

    it("refuses a pending invite at or past its expiry, before the sweep marks it", () => {
        expect(isActionable(anInvite({ expiresAt }), now)).toBe(false);
        expect(isActionable(anInvite({ expiresAt: asIsoInstant("2026-01-14T12:00:00Z") }), now)).toBe(false);
    });

    it.each(["ACCEPTED", "REVOKED", "EXPIRED"] as const)("refuses an invite that is %s", (status) => {
        expect(isActionable(anInvite({ status }), now)).toBe(false);
    });
});

describe("displayStatus", () => {
    it("reads a lapsed pending invite as expired", () => {
        expect(displayStatus(anInvite({ expiresAt }), now)).toBe("EXPIRED");
    });

    it.each(["PENDING", "ACCEPTED", "REVOKED", "EXPIRED"] as const)("keeps %s otherwise", (status) => {
        expect(displayStatus(anInvite({ status }), now)).toBe(status);
    });
});

describe("canManageInvite", () => {
    it("lets an owner act on any invite", () => {
        expect(canManageInvite("OWNER", anInvite({ role: "ADMIN" }))).toBe(true);
    });

    it("keeps an admin off an invite that grants admin", () => {
        expect(canManageInvite("ADMIN", anInvite({ role: "ADMIN" }))).toBe(false);
        expect(canManageInvite("ADMIN", anInvite({ role: "VIEWER" }))).toBe(true);
    });

    it("keeps a developer off every invite", () => {
        expect(canManageInvite("DEVELOPER", anInvite({ role: "VIEWER" }))).toBe(false);
    });
});

describe("canInviteAgain", () => {
    it.each([
        ["EXPIRED", true],
        ["REVOKED", true],
        ["ACCEPTED", false],
        ["PENDING", false],
    ] as const)("%s → %s", (status, expected) => {
        expect(canInviteAgain(anInvite({ status }), now)).toBe(expected);
    });

    it("offers a lapsed pending invite again", () => {
        expect(canInviteAgain(anInvite({ expiresAt }), now)).toBe(true);
    });
});
