import { asUserId } from "@/shared/domain/ids";
import { asIsoInstant } from "@/shared/domain/instant";
import {
    parseSessionId,
    summarize,
    type IssuedTokens,
    type Session,
    type SessionId,
    type SessionSummary,
} from "../session";

export const SESSION_ID = sessionId("a");
export const USER_ID = asUserId("2f6c3e1a-0000-4000-8000-000000000001");

export function sessionId(fill: string): SessionId {
    const id = parseSessionId(fill.repeat(43));
    if (id === undefined) throw new Error(`Not a session id fill: ${fill}`);
    return id;
}

export function aSession(overrides: Partial<Session> = {}): Session {
    return {
        id: SESSION_ID,
        userId: USER_ID,
        email: "ada@example.com",
        keycloakSessionId: "kc-1",
        accessToken: "access-1",
        accessExpiresAt: asIsoInstant("2026-01-15T12:05:00.000Z"),
        refreshToken: "refresh-1",
        refreshExpiresAt: asIsoInstant("2026-01-15T12:30:00.000Z"),
        createdAt: asIsoInstant("2026-01-15T11:00:00.000Z"),
        ...overrides,
    };
}

export function issuedTokens(overrides: Partial<IssuedTokens> = {}): IssuedTokens {
    return {
        accessToken: "access-2",
        accessExpiresInSeconds: 300,
        refreshToken: "refresh-2",
        refreshExpiresInSeconds: 1800,
        claims: { subject: USER_ID, email: "ada@example.com", sessionId: "kc-1" },
        ...overrides,
    };
}

export function sessionSummary(overrides: Partial<Session> = {}): SessionSummary {
    return summarize(aSession(overrides));
}
