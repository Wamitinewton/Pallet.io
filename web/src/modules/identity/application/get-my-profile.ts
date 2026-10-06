import type { Profile } from "../domain/profile";
import type { AccountRepository } from "./ports";

export type GetMyProfile = () => Promise<Profile>;

export function makeGetMyProfile(accounts: AccountRepository): GetMyProfile {
    return () => accounts.getMyProfile();
}
