import type { Session } from "../domain/session";
import type { RequestOrigin, SessionStore, TokenIssuer } from "./ports";
import type { ReplaceTokens } from "./replace-tokens";

export interface ReauthenticateCommand {
    readonly session: Session;
    readonly password: string;
    readonly origin: RequestOrigin;
}

export type ReauthenticateResult =
    | { readonly status: "reauthenticated"; readonly session: Session; readonly previousRevoked: boolean }
    | { readonly status: "different-user" }
    | { readonly status: "ended" }
    | { readonly status: "contended" };

export type Reauthenticate = (command: ReauthenticateCommand) => Promise<ReauthenticateResult>;

export interface ReauthenticateDependencies {
    readonly store: SessionStore;
    readonly issuer: TokenIssuer;
    readonly replaceTokens: ReplaceTokens;
    readonly maxAttempts?: number;
}

/**
 * Step-up: a fresh password grant whose tokens replace the session's under the same session id, then the
 * previous Keycloak session is revoked so step-up never leaves an orphan in the account's session list.
 * Identity's errors (a wrong password, a rate limit) propagate untouched, and nothing is replaced.
 */
export function makeReauthenticate({
    store,
    issuer,
    replaceTokens,
    maxAttempts = 3,
}: ReauthenticateDependencies): Reauthenticate {
    const revokeQuietly = async (tokens: Pick<Session, "accessToken" | "refreshToken">, origin: RequestOrigin) => {
        try {
            await issuer.revoke(tokens, origin);
            return true;
        } catch {
            return false;
        }
    };

    return async ({ session, password, origin }) => {
        const issued = await issuer.login({ email: session.email, password }, origin);

        let current = session;
        for (let attempt = 0; attempt < maxAttempts; attempt++) {
            const result = await replaceTokens(current, issued);
            switch (result.status) {
                case "replaced": {
                    const previousRevoked = await revokeQuietly(
                        { accessToken: issued.accessToken, refreshToken: result.previous.refreshToken },
                        origin,
                    );
                    return { status: "reauthenticated", session: result.session, previousRevoked };
                }
                case "different-user":
                    await revokeQuietly(issued, origin);
                    return { status: "different-user" };
                case "stale": {
                    const latest = await store.get(current.id);
                    if (latest === undefined) {
                        await revokeQuietly(issued, origin);
                        return { status: "ended" };
                    }
                    current = latest;
                }
            }
        }

        await revokeQuietly(issued, origin);
        return { status: "contended" };
    };
}
