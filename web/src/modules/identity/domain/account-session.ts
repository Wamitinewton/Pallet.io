import { ApiError } from "@/shared/domain/errors";
import type { Brand } from "@/shared/domain/ids";
import type { IsoInstant } from "@/shared/domain/instant";

export type AccountSessionId = Brand<string, "AccountSessionId">;

export const asAccountSessionId = (value: string) => value as AccountSessionId;

export const SESSION_NOT_FOUND = "SESSION_NOT_FOUND";

/** One signed-in Keycloak session of the caller's account; the id is internal and never shown. */
export interface AccountSession {
    readonly id: AccountSessionId;
    readonly ipAddress: string | undefined;
    readonly startedAt: IsoInstant;
    readonly lastAccessedAt: IsoInstant;
}

/** `currentId` is the Keycloak session behind this browser's BFF session, when the token named one. */
export function isCurrent(session: AccountSession, currentId: string | undefined): boolean {
    return currentId !== undefined && session.id === currentId;
}

export function byRecentActivity(sessions: readonly AccountSession[]): AccountSession[] {
    return sessions.toSorted(
        (a, b) =>
            Date.parse(b.lastAccessedAt) - Date.parse(a.lastAccessedAt) ||
            Date.parse(b.startedAt) - Date.parse(a.startedAt),
    );
}

export function otherSessions(sessions: readonly AccountSession[], currentId: string | undefined): AccountSession[] {
    return sessions.filter((session) => !isCurrent(session, currentId));
}

export function withoutSession(sessions: readonly AccountSession[], id: AccountSessionId): AccountSession[] {
    return sessions.filter((session) => session.id !== id);
}

/** Ending a session that has already ended is the outcome the caller asked for. */
export function isAlreadyEnded(error: unknown): boolean {
    return error instanceof ApiError && error.status === 404 && error.is(SESSION_NOT_FOUND);
}
