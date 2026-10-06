import { isAlreadyEnded, type AccountSessionId } from "../domain/account-session";
import type { AccountRepository } from "./ports";

export type RevokeSession = (id: AccountSessionId) => Promise<void>;

export function makeRevokeSession(accounts: AccountRepository): RevokeSession {
    return async (id) => {
        try {
            await accounts.revokeSession(id);
        } catch (error) {
            if (!isAlreadyEnded(error)) throw error;
        }
    };
}
