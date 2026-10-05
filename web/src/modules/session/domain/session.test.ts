import { asUserId } from "@/shared/domain/ids";
import { describe, expect, it } from "vitest";
import { belongsToSameUser, openSession, parseSessionId, rotateTokens, summarize } from "./session";
import { aSession, issuedTokens, SESSION_ID } from "./testing/fixtures";

const now = new Date("2026-01-15T12:00:00.000Z");

describe("parseSessionId", () => {
    it("accepts exactly 43 base64url characters, the encoding of 32 random bytes", () => {
        expect(parseSessionId("A-_z".repeat(10) + "abc")).toBeDefined();
        expect(parseSessionId("a".repeat(42))).toBeUndefined();
        expect(parseSessionId("a".repeat(44))).toBeUndefined();
        expect(parseSessionId(`${"a".repeat(42)}=`)).toBeUndefined();
    });
});

describe("openSession", () => {
    it("derives expiries from the issued lifetimes and identity from the claims", () => {
        expect(openSession(SESSION_ID, issuedTokens(), "typed@example.com", now)).toEqual({
            id: SESSION_ID,
            userId: issuedTokens().claims.subject,
            email: "ada@example.com",
            keycloakSessionId: "kc-1",
            accessToken: "access-2",
            accessExpiresAt: "2026-01-15T12:05:00.000Z",
            refreshToken: "refresh-2",
            refreshExpiresAt: "2026-01-15T12:30:00.000Z",
            createdAt: "2026-01-15T12:00:00.000Z",
        });
    });

    it("falls back to the sign-in email when the token carries none", () => {
        const issued = issuedTokens({ claims: { ...issuedTokens().claims, email: undefined } });
        expect(openSession(SESSION_ID, issued, "typed@example.com", now).email).toBe("typed@example.com");
    });
});

describe("rotateTokens", () => {
    it("keeps the id, user and creation time and replaces the tokens", () => {
        const rotated = rotateTokens(
            aSession(),
            issuedTokens({ claims: { ...issuedTokens().claims, sessionId: undefined } }),
            now,
        );
        expect(rotated).toMatchObject({
            id: SESSION_ID,
            createdAt: aSession().createdAt,
            keycloakSessionId: "kc-1",
            accessToken: "access-2",
            refreshToken: "refresh-2",
            accessExpiresAt: "2026-01-15T12:05:00.000Z",
        });
    });
});

describe("belongsToSameUser", () => {
    it("compares the subject", () => {
        expect(belongsToSameUser(aSession(), issuedTokens())).toBe(true);
        const stranger = issuedTokens({ claims: { ...issuedTokens().claims, subject: asUserId("someone-else") } });
        expect(belongsToSameUser(aSession(), stranger)).toBe(false);
    });
});

describe("summarize", () => {
    it("never carries a token", () => {
        const summary = JSON.stringify(summarize(aSession()));
        expect(summary).not.toContain("access-1");
        expect(summary).not.toContain("refresh-1");
        expect(summary).not.toContain(SESSION_ID);
    });
});
