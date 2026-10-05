import type { Clock } from "@/shared/domain/clock";
import { openSession, parseSessionId, type Session } from "../domain/session";
import type { PasswordGrant, RequestOrigin, SessionIdGenerator, SessionStore, TokenIssuer } from "./ports";

export interface StartSessionCommand {
    readonly grant: PasswordGrant;
    readonly origin: RequestOrigin;
    readonly replacing?: string | undefined;
}

export type StartSession = (command: StartSessionCommand) => Promise<Session>;

export interface StartSessionDependencies {
    readonly store: SessionStore;
    readonly issuer: TokenIssuer;
    readonly ids: SessionIdGenerator;
    readonly clock: Clock;
}

export function makeStartSession({ store, issuer, ids, clock }: StartSessionDependencies): StartSession {
    return async ({ grant, origin, replacing }) => {
        const issued = await issuer.login(grant, origin);
        const previous = replacing === undefined ? undefined : parseSessionId(replacing);
        if (previous !== undefined) await store.delete(previous);

        const session = openSession(ids.next(), issued, grant.email, clock.now());
        await store.create(session);
        return session;
    };
}
