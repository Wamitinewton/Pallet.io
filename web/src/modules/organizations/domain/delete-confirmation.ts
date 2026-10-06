import { ApiError } from "@/shared/domain/errors";

export const CONFIRMATION_MISMATCH = "CONFIRMATION_MISMATCH";
export const PERSONAL_ORG_IMMUTABLE = "PERSONAL_ORG_IMMUTABLE";

/** Exactly the slug, as the backend compares it: no trimming, no case folding. */
export function confirmsDeletion(slug: string, typed: string): boolean {
    return typed === slug;
}

export class DeletionNotConfirmedError extends Error {
    override readonly name = "DeletionNotConfirmedError";

    constructor() {
        super("The typed confirmation doesn't match the organization's slug");
    }
}

export function isConfirmationMismatch(error: unknown): boolean {
    return error instanceof DeletionNotConfirmedError || (error instanceof ApiError && error.is(CONFIRMATION_MISMATCH));
}
