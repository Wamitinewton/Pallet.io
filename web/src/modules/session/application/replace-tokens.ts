import type { Clock } from "@/shared/domain/clock";
import { belongsToSameUser, rotateTokens, type IssuedTokens, type Session } from "../domain/session";
import type { SessionStore } from "./ports";

export type ReplaceTokensResult =
    | { readonly status: "replaced"; readonly session: Session; readonly previous: Session }
    | { readonly status: "stale" }
    | { readonly status: "different-user" };

export type ReplaceTokens = (current: Session, issued: IssuedTokens) => Promise<ReplaceTokensResult>;

export function makeReplaceTokens({ store, clock }: { store: SessionStore; clock: Clock }): ReplaceTokens {
    return async (current, issued) => {
        if (!belongsToSameUser(current, issued)) return { status: "different-user" };
        const next = rotateTokens(current, issued, clock.now());
        const replaced = await store.replaceTokens(next, current.refreshToken);
        return replaced ? { status: "replaced", session: next, previous: current } : { status: "stale" };
    };
}
