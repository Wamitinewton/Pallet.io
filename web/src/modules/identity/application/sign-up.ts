import { signupSchema, type SignupDetails, type SignupReceipt } from "../domain/signup";
import { isTakenConflict, SignupConflictError, type TakenField } from "../domain/signup-failure";
import type { SignupRepository } from "./ports";

export type SignUp = (details: SignupDetails, idempotencyKey: string) => Promise<SignupReceipt>;

async function takenField(signups: SignupRepository, slug: string): Promise<TakenField | undefined> {
    try {
        return (await signups.checkSlug(slug)).available ? "email" : "slug";
    } catch {
        return undefined;
    }
}

/** @throws SignupConflictError when the slug or the email is taken, naming which when a re-check can tell. */
export function makeSignUp(signups: SignupRepository): SignUp {
    return async (details, idempotencyKey) => {
        const valid = signupSchema.parse(details);
        try {
            return await signups.signUp(valid, idempotencyKey);
        } catch (error) {
            if (!isTakenConflict(error)) throw error;
            throw new SignupConflictError(await takenField(signups, valid.slug), { cause: error });
        }
    };
}
