import { z } from "zod";

export const MIN_PASSWORD_LENGTH = 12;
export const MAX_STRENGTH = 4;

const CHARACTER_CLASSES = [/\p{Ll}/u, /\p{Lu}/u, /\p{N}/u, /[^\p{L}\p{N}]/u];
const REPEATED_RUN = /(.)\1{2,}/u;
const LENGTH_STEPS = [MIN_PASSWORD_LENGTH, 16, 20];

/** The backend's rule, and the only one that blocks: at least 12 characters, not all blank. */
export function meetsPolicy(password: string): boolean {
    return password.length >= MIN_PASSWORD_LENGTH && password.trim() !== "";
}

export const passwordSchema = z.string().refine(meetsPolicy, `Use at least ${String(MIN_PASSWORD_LENGTH)} characters`);

/** A 0–4 estimate for the strength meter; it informs and never blocks. */
export function strength(password: string): number {
    if (password === "") return 0;

    const lengthScore = LENGTH_STEPS.filter((step) => password.length >= step).length;
    const classes = CHARACTER_CLASSES.filter((pattern) => pattern.test(password)).length;
    const varietyScore = classes >= 3 ? 1 : 0;
    const penalty = REPEATED_RUN.test(password) ? 1 : 0;
    const score = Math.max(1, Math.min(MAX_STRENGTH, lengthScore + varietyScore - penalty));

    return meetsPolicy(password) ? score : 1;
}
