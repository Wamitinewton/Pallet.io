import { z } from "zod";

/** Mirrors the backend's DNS-1123 label rule: lowercase alphanumeric words joined by hyphens. */
export const SLUG_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/;
export const MAX_SLUG_LENGTH = 63;

export const SLUG_TOO_LONG_MESSAGE = `Keep the URL to ${String(MAX_SLUG_LENGTH)} characters or fewer`;
export const SLUG_FORMAT_MESSAGE = "Use lowercase letters, numbers and single hyphens, with no hyphen at either end";

/** The slug the backend would derive from a name; empty when the name has nothing usable. */
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

/** Empty means "derive it from the name", which the backend does when the slug is left out. */
export const optionalSlugSchema = z
    .string()
    .trim()
    .max(MAX_SLUG_LENGTH, SLUG_TOO_LONG_MESSAGE)
    .refine((slug) => slug === "" || SLUG_PATTERN.test(slug), SLUG_FORMAT_MESSAGE)
    .optional()
    .transform((slug) => (slug === "" ? undefined : slug));
