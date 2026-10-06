import type { IsoInstant } from "@/shared/domain/instant";

/** The caller's own GitHub sign-in on Pallet, whichever organization is open; it lasts at most an hour. */
export interface GitHubSession {
    readonly githubLogin: string;
    readonly expiresAt: IsoInstant;
}
