import type { Brand, UserId } from "@/shared/domain/ids";
import { instantFromDate, type IsoInstant } from "@/shared/domain/instant";

export type SessionId = Brand<string, "SessionId">;

const SESSION_ID_PATTERN = /^[A-Za-z0-9_-]{43}$/;

export function parseSessionId(value: string): SessionId | undefined {
    return SESSION_ID_PATTERN.test(value) ? (value as SessionId) : undefined;
}

export interface TokenClaims {
    readonly subject: UserId;
    readonly email: string | undefined;
    readonly sessionId: string | undefined;
}

export interface IssuedTokens {
    readonly accessToken: string;
    readonly accessExpiresInSeconds: number;
    readonly refreshToken: string;
    readonly refreshExpiresInSeconds: number;
    readonly claims: TokenClaims;
}

export interface Session {
    readonly id: SessionId;
    readonly userId: UserId;
    readonly email: string;
    readonly keycloakSessionId: string | undefined;
    readonly accessToken: string;
    readonly accessExpiresAt: IsoInstant;
    readonly refreshToken: string;
    readonly refreshExpiresAt: IsoInstant;
    readonly createdAt: IsoInstant;
}

export interface SessionSummary {
    readonly userId: UserId;
    readonly email: string;
    readonly keycloakSessionId: string | undefined;
    readonly accessExpiresAt: IsoInstant;
}

const after = (now: Date, seconds: number) => instantFromDate(new Date(now.getTime() + seconds * 1000));

export function openSession(id: SessionId, issued: IssuedTokens, signInEmail: string, now: Date): Session {
    return {
        id,
        userId: issued.claims.subject,
        email: issued.claims.email ?? signInEmail,
        keycloakSessionId: issued.claims.sessionId,
        accessToken: issued.accessToken,
        accessExpiresAt: after(now, issued.accessExpiresInSeconds),
        refreshToken: issued.refreshToken,
        refreshExpiresAt: after(now, issued.refreshExpiresInSeconds),
        createdAt: instantFromDate(now),
    };
}

export function belongsToSameUser(session: Session, issued: IssuedTokens): boolean {
    return session.userId === issued.claims.subject;
}

export function rotateTokens(session: Session, issued: IssuedTokens, now: Date): Session {
    return {
        ...session,
        email: issued.claims.email ?? session.email,
        keycloakSessionId: issued.claims.sessionId ?? session.keycloakSessionId,
        accessToken: issued.accessToken,
        accessExpiresAt: after(now, issued.accessExpiresInSeconds),
        refreshToken: issued.refreshToken,
        refreshExpiresAt: after(now, issued.refreshExpiresInSeconds),
    };
}

export function summarize(session: Session): SessionSummary {
    return {
        userId: session.userId,
        email: session.email,
        keycloakSessionId: session.keycloakSessionId,
        accessExpiresAt: session.accessExpiresAt,
    };
}
