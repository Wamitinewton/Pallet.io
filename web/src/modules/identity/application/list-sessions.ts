import { byRecentActivity, type AccountSession } from "../domain/account-session";
import type { AccountRepository } from "./ports";

export type ListSessions = () => Promise<readonly AccountSession[]>;

export function makeListSessions(accounts: AccountRepository): ListSessions {
    return async () => byRecentActivity(await accounts.listSessions());
}
