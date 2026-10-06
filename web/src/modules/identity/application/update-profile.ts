import { profileUpdateSchema, type ProfileUpdate } from "../domain/profile";
import type { AccountRepository } from "./ports";

export type UpdateProfile = (update: ProfileUpdate) => Promise<void>;

export function makeUpdateProfile(accounts: AccountRepository): UpdateProfile {
    return async (update) => {
        await accounts.updateProfile(profileUpdateSchema.parse(update));
    };
}
