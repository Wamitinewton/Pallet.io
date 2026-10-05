import { parseSessionId } from "../domain/session";
import type { RequestOrigin, SessionStore, TokenIssuer } from "./ports";
import type { ResolveSession } from "./resolve-session";

export interface EndSessionResult {
    readonly ended: boolean;
    readonly revoked: boolean;
}

export type EndSession = (cookieValue: string | undefined, origin?: RequestOrigin) => Promise<EndSessionResult>;

export interface EndSessionDependencies {
    readonly store: SessionStore;
    readonly issuer: TokenIssuer;
    readonly resolveSession: ResolveSession;
}

export function makeEndSession({ store, issuer, resolveSession }: EndSessionDependencies): EndSession {
    return async (cookieValue, origin = {}) => {
        const id = cookieValue === undefined ? undefined : parseSessionId(cookieValue);
        if (id === undefined) return { ended: false, revoked: false };

        try {
            const resolved = await resolveSession(cookieValue, origin);
            if (resolved.status !== "active") return { ended: false, revoked: false };
            await issuer.revoke(resolved.session, origin);
            return { ended: true, revoked: true };
        } catch {
            return { ended: true, revoked: false };
        } finally {
            await store.delete(id);
        }
    };
}
