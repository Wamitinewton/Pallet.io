import type { Clock } from "@/shared/domain/clock";
import { parseSessionId, type Session } from "../domain/session";
import { hasValidAccessToken, isRefreshable, needsRefresh } from "../domain/session-policy";
import type { RefreshLock, RequestOrigin, SessionStore, Sleep, TokenIssuer } from "./ports";
import { TokenRejectedError } from "./ports";
import type { ReplaceTokens } from "./replace-tokens";

export type ResolvedSession =
    | { readonly status: "none" }
    | { readonly status: "expired" }
    | { readonly status: "active"; readonly session: Session; readonly refreshed: boolean };

export type ResolveSession = (cookieValue: string | undefined, origin?: RequestOrigin) => Promise<ResolvedSession>;

export class RefreshTimeoutError extends Error {
    override readonly name = "RefreshTimeoutError";

    constructor() {
        super("Timed out waiting for a concurrent session refresh");
    }
}

export interface ResolveSessionDependencies {
    readonly store: SessionStore;
    readonly issuer: TokenIssuer;
    readonly lock: RefreshLock;
    readonly replaceTokens: ReplaceTokens;
    readonly clock: Clock;
    readonly sleep: Sleep;
    readonly pollIntervalMs?: number;
    readonly pollAttempts?: number;
}

const NONE: ResolvedSession = { status: "none" };
const EXPIRED: ResolvedSession = { status: "expired" };
const active = (session: Session, refreshed = false): ResolvedSession => ({ status: "active", session, refreshed });

export function makeResolveSession({
    store,
    issuer,
    lock,
    replaceTokens,
    clock,
    sleep,
    pollIntervalMs = 100,
    pollAttempts = 50,
}: ResolveSessionDependencies): ResolveSession {
    const fallBackTo = (session: Session, error: unknown): ResolvedSession => {
        if (hasValidAccessToken(session, clock)) return active(session);
        throw error;
    };

    const refresh = async (current: Session, origin: RequestOrigin): Promise<ResolvedSession> => {
        let issued;
        try {
            issued = await issuer.refresh(current.refreshToken, origin);
        } catch (error) {
            if (!(error instanceof TokenRejectedError)) return fallBackTo(current, error);
            await store.delete(current.id);
            return EXPIRED;
        }

        const result = await replaceTokens(current, issued);
        switch (result.status) {
            case "replaced":
                return active(result.session, true);
            case "different-user":
                await store.delete(current.id);
                return EXPIRED;
            case "stale": {
                const latest = await store.get(current.id);
                return latest === undefined ? EXPIRED : active(latest);
            }
        }
    };

    const awaitConcurrentRefresh = async (seen: Session): Promise<ResolvedSession> => {
        for (let attempt = 0; attempt < pollAttempts; attempt++) {
            await sleep(pollIntervalMs);
            const current = await store.get(seen.id);
            if (current === undefined) return EXPIRED;
            if (current.accessToken !== seen.accessToken) return active(current);
        }
        return fallBackTo(seen, new RefreshTimeoutError());
    };

    const refreshOnce = async (seen: Session, origin: RequestOrigin): Promise<ResolvedSession> => {
        const lease = await lock.acquire(seen.id);
        if (lease === undefined) return awaitConcurrentRefresh(seen);
        try {
            const current = await store.get(seen.id);
            if (current === undefined) return EXPIRED;
            if (!needsRefresh(current, clock)) return active(current);
            return await refresh(current, origin);
        } finally {
            await lease.release();
        }
    };

    return async (cookieValue, origin = {}) => {
        if (cookieValue === undefined || cookieValue === "") return NONE;
        const id = parseSessionId(cookieValue);
        if (id === undefined) return EXPIRED;

        const session = await store.get(id);
        if (session === undefined) return EXPIRED;
        if (!isRefreshable(session, clock)) {
            await store.delete(id);
            return EXPIRED;
        }
        return needsRefresh(session, clock) ? refreshOnce(session, origin) : active(session);
    };
}
