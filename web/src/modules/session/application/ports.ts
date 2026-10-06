import type { Credentials } from "../domain/credentials";
import type { Reauthentication } from "../domain/reauthentication";
import type { IssuedTokens, Session, SessionId, SessionSummary } from "../domain/session";

/** The browser's view of the session: it can start, read and end one, and never sees a token. */
export interface SessionGateway {
    signIn(credentials: Credentials): Promise<void>;
    signOut(): Promise<void>;
    summary(): Promise<SessionSummary>;
    reauthenticate(reauthentication: Reauthentication): Promise<void>;
}

export interface SessionStore {
    create(session: Session): Promise<void>;
    get(id: SessionId): Promise<Session | undefined>;
    replaceTokens(next: Session, expectedRefreshToken: string): Promise<boolean>;
    delete(id: SessionId): Promise<void>;
}

export interface RequestOrigin {
    readonly clientIp?: string | undefined;
    readonly correlationId?: string | undefined;
}

export interface PasswordGrant {
    readonly email: string;
    readonly password: string;
}

export interface TokenIssuer {
    login(grant: PasswordGrant, origin: RequestOrigin): Promise<IssuedTokens>;
    /** @throws TokenRejectedError when identity refuses the refresh token; anything else is transient. */
    refresh(refreshToken: string, origin: RequestOrigin): Promise<IssuedTokens>;
    revoke(session: Pick<Session, "accessToken" | "refreshToken">, origin: RequestOrigin): Promise<void>;
}

export class TokenRejectedError extends Error {
    override readonly name = "TokenRejectedError";
}

export interface SessionIdGenerator {
    next(): SessionId;
}

export interface RefreshLease {
    release(): Promise<void>;
}

export interface RefreshLock {
    acquire(id: SessionId): Promise<RefreshLease | undefined>;
}

export type Sleep = (millis: number) => Promise<void>;
