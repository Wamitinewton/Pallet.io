import { fixedClock } from "@/shared/domain/clock";
import { ROLES } from "@/shared/domain/role";
import { describe, expect, it } from "vitest";
import { INVITED_ROLE_DESCRIPTION, unavailableReason } from "./invite-preview";

const now = fixedClock("2026-01-15T12:00:00Z").now();

describe("unavailableReason", () => {
    it("reads a link at or past its expiry as expired", () => {
        expect(unavailableReason(new Date("2026-01-15T12:00:00Z"), now)).toBe("expired");
        expect(unavailableReason(new Date("2026-01-12T12:00:00Z"), now)).toBe("expired");
    });

    it("reads a link that hasn't expired as withdrawn: it was revoked or already used", () => {
        expect(unavailableReason(new Date("2026-01-15T12:00:01Z"), now)).toBe("withdrawn");
    });

    it("says only what is true either way when the expiry isn't known", () => {
        expect(unavailableReason(undefined, now)).toBe("withdrawn");
    });
});

describe("INVITED_ROLE_DESCRIPTION", () => {
    it("describes every role", () => {
        for (const role of ROLES) expect(INVITED_ROLE_DESCRIPTION[role]).not.toBe("");
    });
});
