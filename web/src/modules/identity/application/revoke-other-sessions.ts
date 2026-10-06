import type { AccountRepository } from "./ports";

export type RevokeOtherSessions = () => Promise<void>;

export function makeRevokeOtherSessions(accounts: AccountRepository): RevokeOtherSessions {
    return () => accounts.revokeOtherSessions();
}
