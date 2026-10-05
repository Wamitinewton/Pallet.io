import { emailSchema } from "@/shared/domain/email";
import type { OrgId } from "@/shared/domain/ids";
import { z } from "zod";
import { passwordSchema } from "./password-policy";

/** Mirrors the backend's `SLUG_PATTERN`: lowercase alphanumeric words joined by single hyphens. */
export const SLUG_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/;
export const MAX_SLUG_LENGTH = 63;
const MAX_SUGGESTIONS = 2;
const SUGGESTION_SUFFIXES = ["hq", "team", "app"];

export const slugSchema = z
    .string()
    .min(1, "Choose a URL for your organization")
    .max(MAX_SLUG_LENGTH, `Keep the URL to ${String(MAX_SLUG_LENGTH)} characters or fewer`)
    .regex(SLUG_PATTERN, "Use lowercase letters, numbers and single hyphens, with no hyphen at either end");

export function slugProblem(slug: string): string | undefined {
    return slugSchema.safeParse(slug).error?.issues[0]?.message;
}

export function isValidSlug(slug: string): boolean {
    return slugProblem(slug) === undefined;
}

export function slugFromName(name: string): string {
    return name
        .normalize("NFKD")
        .replace(/\p{M}+/gu, "")
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, "-")
        .replace(/^-+|-+$/g, "")
        .slice(0, MAX_SLUG_LENGTH)
        .replace(/-+$/, "");
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
