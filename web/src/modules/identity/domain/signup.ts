import { emailSchema } from "@/shared/domain/email";
import type { OrgId } from "@/shared/domain/ids";
import { passwordSchema } from "@/shared/domain/password-policy";
import {
    MAX_SLUG_LENGTH,
    SLUG_FORMAT_MESSAGE,
    SLUG_PATTERN,
    SLUG_TOO_LONG_MESSAGE,
    slugFromName,
} from "@/shared/domain/slug";
import { z } from "zod";

export { MAX_SLUG_LENGTH, slugFromName } from "@/shared/domain/slug";

const MAX_SUGGESTIONS = 2;
const SUGGESTION_SUFFIXES = ["hq", "team", "app"];

export const slugSchema = z
    .string()
    .min(1, "Choose a URL for your organization")
    .max(MAX_SLUG_LENGTH, SLUG_TOO_LONG_MESSAGE)
    .regex(SLUG_PATTERN, SLUG_FORMAT_MESSAGE);

export function slugProblem(slug: string): string | undefined {
    return slugSchema.safeParse(slug).error?.issues[0]?.message;
}

export function isValidSlug(slug: string): boolean {
    return slugProblem(slug) === undefined;
}

/** Alternatives to offer for a taken slug; they are unchecked, so the check runs again once one is picked. */
export function suggestSlugs(taken: string, organizationName: string): readonly string[] {
    const candidates = [slugFromName(organizationName), ...SUGGESTION_SUFFIXES.map((suffix) => `${taken}-${suffix}`)];
    return [...new Set(candidates)]
        .filter((candidate) => candidate !== taken && isValidSlug(candidate))
        .slice(0, MAX_SUGGESTIONS);
}

export const signupSchema = z.object({
    organizationName: z.string().trim().min(1, "Enter your organization's name"),
    slug: slugSchema,
    displayName: z.string().trim().min(1, "Enter your name"),
    email: emailSchema,
    password: passwordSchema,
});

export type SignupDetails = z.output<typeof signupSchema>;

export interface SignupReceipt {
    readonly orgId: OrgId;
    readonly organizationName: string;
    readonly slug: string;
}

export interface SlugAvailability {
    readonly slug: string;
    readonly available: boolean;
}
