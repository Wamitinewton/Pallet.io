import type { Credentials } from "../../domain/credentials";
import type { IssuedTokens, Session, SessionId } from "../../domain/session";
import { issuedTokens, sessionId } from "../../domain/testing/fixtures";
import type { RefreshLock, SessionGateway, SessionIdGenerator, SessionStore, TokenIssuer } from "../ports";

export class ScriptedSessionGateway implements SessionGateway {
    readonly signedIn: Credentials[] = [];
    signOuts = 0;
    nextSignIn: () => Promise<void> = () => Promise.resolve();
    nextSignOut: () => Promise<void> = () => Promise.resolve();

    signIn(credentials: Credentials) {
        this.signedIn.push(credentials);
        return this.nextSignIn();
    }

    signOut() {
        this.signOuts++;
        return this.nextSignOut();
    }
}

export class InMemorySessionStore implements SessionStore {
    readonly sessions = new Map<SessionId, Session>();

    create(session: Session): Promise<void> {
        this.sessions.set(session.id, session);
        return Promise.resolve();
    }

    get(id: SessionId): Promise<Session | undefined> {
        return Promise.resolve(this.sessions.get(id));
    }

    replaceTokens(next: Session, expectedRefreshToken: string): Promise<boolean> {
        const current = this.sessions.get(next.id);
        if (current?.refreshToken !== expectedRefreshToken) return Promise.resolve(false);
        this.sessions.set(next.id, next);
        return Promise.resolve(true);
    }

    delete(id: SessionId): Promise<void> {
        this.sessions.delete(id);
        return Promise.resolve();
    }
}

export class InMemoryRefreshLock implements RefreshLock {
    readonly held = new Set<SessionId>();

    acquire(id: SessionId) {
        if (this.held.has(id)) return Promise.resolve(undefined);
        this.held.add(id);
        return Promise.resolve({
            release: () => {
                this.held.delete(id);
                return Promise.resolve();
            },
        });
    }
}

export class ScriptedTokenIssuer implements TokenIssuer {
    readonly refreshed: string[] = [];
    readonly revoked: string[] = [];
    readonly loggedIn: string[] = [];
    nextLogin: () => Promise<IssuedTokens> = () => Promise.resolve(issuedTokens());
    nextRefresh: () => Promise<IssuedTokens> = () => Promise.resolve(issuedTokens());
    nextRevoke: () => Promise<void> = () => Promise.resolve();

    login(grant: { email: string }) {
        this.loggedIn.push(grant.email);
        return this.nextLogin();
    }

    refresh(refreshToken: string) {
        this.refreshed.push(refreshToken);
        return this.nextRefresh();
    }

    revoke(session: { refreshToken: string }) {
        this.revoked.push(session.refreshToken);
        return this.nextRevoke();
    }
}

export function sequentialSessionIds(...fills: string[]): SessionIdGenerator {
    const queue = [...fills];
    return {
        next() {
            const fill = queue.shift();
            if (fill === undefined) throw new Error("No more session ids");
            return sessionId(fill);
        },
    };
}
