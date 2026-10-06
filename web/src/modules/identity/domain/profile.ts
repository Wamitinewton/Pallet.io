import type { UserId } from "@/shared/domain/ids";
import { z } from "zod";

export const ACCOUNT_STATUSES = ["ACTIVE", "DISABLED"] as const;

export type AccountStatus = (typeof ACCOUNT_STATUSES)[number];

export interface Profile {
    readonly userId: UserId;
    readonly email: string;
    readonly displayName: string;
    readonly status: AccountStatus;
}

export const displayNameSchema = z.string().trim().min(1, "Enter your name");

export const profileUpdateSchema = z.object({
    displayName: displayNameSchema,
});

export type ProfileUpdate = z.output<typeof profileUpdateSchema>;

export function renamed(profile: Profile, { displayName }: ProfileUpdate): Profile {
    return { ...profile, displayName };
}

/** Surrounding blanks never count as a change, since the name is saved trimmed. */
export function isUnchanged(profile: Profile, typed: string): boolean {
    return typed.trim() === profile.displayName;
}
