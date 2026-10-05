import { slugSchema, type SlugAvailability } from "../domain/signup";
import type { CancellableRequest, SignupRepository } from "./ports";

export type CheckSlugAvailability = (slug: string, request?: CancellableRequest) => Promise<SlugAvailability>;

export function makeCheckSlugAvailability(signups: SignupRepository): CheckSlugAvailability {
    return async (slug, request) => signups.checkSlug(slugSchema.parse(slug), request);
}
