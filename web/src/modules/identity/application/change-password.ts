import { passwordChangeSchema, type PasswordChange } from "../domain/change-password";
import type { AccountRepository } from "./ports";

export type ChangePassword = (change: PasswordChange) => Promise<void>;

export function makeChangePassword(accounts: AccountRepository): ChangePassword {
    return async (change) => {
        await accounts.changePassword(passwordChangeSchema.parse(change));
    };
}
