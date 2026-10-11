import { z } from "zod";
import { ruleMessage } from "./rule-message";

/** `ApiDocs.ROOT_DIRECTORY_PATTERN` in git-integration-service, verbatim: change both together. */
export const ROOT_DIRECTORY_PATTERN = new RegExp(
    String.raw`^$|^(?!/)(?!.*\.\.)(?!.*//)(?!(.*/)?\.(/|$))[^\\\x00-\x1f\x7f-\x9f]{1,255}$`,
    "u",
);

/** `ApiDocs.ROOT_DIRECTORY_RULES`, verbatim. */
export const ROOT_DIRECTORY_RULES =
    "A path relative to the repository root, at most 255 characters, with no '..', no '.' or empty segment, no " +
    "backslash and no control character; a trailing '/' is dropped. Empty means the repository root. Anything else " +
    "is 400 INVALID_ROOT_DIRECTORY.";

export const MAX_ROOT_DIRECTORY_LENGTH = 255;

/** The repository root, as the form and the domain hold it. */
export const REPOSITORY_ROOT = "";

/** Java's `Character.isISOControl`: C0, DEL and C1. */
function isIsoControl(character: string): boolean {
    const code = character.charCodeAt(0);
    return code <= 0x1f || (code >= 0x7f && code <= 0x9f);
}

/** The first rule a directory breaks, in the backend's order and words (`RootDirectory.normalize`). */
export function rootDirectoryViolation(value: string): string | undefined {
    if (value === REPOSITORY_ROOT) return undefined;
    if (value.length > MAX_ROOT_DIRECTORY_LENGTH) {
        return `must be at most ${String(MAX_ROOT_DIRECTORY_LENGTH)} characters`;
    }
    if (value.startsWith("/")) return "must be relative to the repository root";
    for (const character of value) {
        if (character === "\\") return "must not contain a backslash";
        if (isIsoControl(character)) return "must not contain control characters";
    }
    if (value.includes("..")) return "must not contain '..'";
    for (const segment of dropTrailingSlash(value).split("/")) {
        if (segment === "") return "must not contain an empty segment";
        if (segment === ".") return "must not contain a '.' segment";
    }
    return undefined;
}

function dropTrailingSlash(value: string): string {
    return value.endsWith("/") ? value.slice(0, -1) : value;
}

/** What the backend stores for a valid directory: no trailing `/`, and empty for the repository root. */
export function normalizeRootDirectory(value: string): string {
    return dropTrailingSlash(value);
}

export const rootDirectorySchema = z
    .string()
    .trim()
    .transform((value, context) => {
        const violation = rootDirectoryViolation(value);
        if (violation !== undefined) context.addIssue({ code: "custom", message: ruleMessage(violation) });
        return normalizeRootDirectory(value);
    });
