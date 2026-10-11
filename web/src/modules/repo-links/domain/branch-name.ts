import { z } from "zod";
import { ruleMessage } from "./rule-message";

/**
 * `ApiDocs.BRANCH_PATTERN` in git-integration-service, verbatim: change both together. The `u` flag counts
 * characters as Java does; the byte limit the pattern can't express is checked by `branchNameViolation`.
 */
export const BRANCH_PATTERN = new RegExp(
    String.raw`^(?!-)(?!/)(?!.*/$)(?!.*\.$)(?!.*\.\.)(?!.*//)(?!.*@\{)(?!@$)` +
        String.raw`(?!(.*/)?\.)(?!.*\.lock(/|$))[^\x00-\x20\x7f~^:?*\[\\]{1,255}$`,
    "u",
);

/** `ApiDocs.BRANCH_RULES`, verbatim. */
export const BRANCH_RULES =
    "A Git branch name: at most 255 bytes, no spaces, control characters or any of ~^:?*[\\, no '..', '@{' or '//', " +
    "not starting with '-' or '/', not ending with '/' or '.', and no component starting with '.' or ending with '.lock'.";

export const MAX_BRANCH_BYTES = 255;

const FORBIDDEN = "~^:?*[\\ ";

function hasForbiddenCharacter(name: string): boolean {
    for (const character of name) {
        const code = character.codePointAt(0) ?? 0;
        if (code < 0x20 || code === 0x7f || FORBIDDEN.includes(character)) return true;
    }
    return false;
}

/** The first rule a name breaks, in the backend's order and words (`PayloadParser.refNameViolation`). */
export function branchNameViolation(name: string): string | undefined {
    if (name === "") return "must name a branch or tag";
    if (new TextEncoder().encode(name).length > MAX_BRANCH_BYTES) {
        return `must be at most ${String(MAX_BRANCH_BYTES)} bytes`;
    }
    if (name.startsWith("-")) return "must not start with '-'";
    if (name.includes("..")) return "must not contain '..'";
    if (name.includes("@{") || name === "@") return "must not contain '@{' or be '@'";
    if (hasForbiddenCharacter(name)) return "must not contain control characters, spaces, or any of ~^:?*[\\";
    if (name.startsWith("/") || name.endsWith("/") || name.includes("//")) {
        return "must not start or end with '/' or contain '//'";
    }
    if (name.endsWith(".")) return "must not end with '.'";
    if (name.split("/").some((component) => component.startsWith(".") || component.endsWith(".lock"))) {
        return "no component may start with '.' or end with '.lock'";
    }
    return undefined;
}

export const ENTER_BRANCH_MESSAGE = "Enter a branch name";

export const branchNameSchema = z
    .string()
    .trim()
    .min(1, ENTER_BRANCH_MESSAGE)
    .superRefine((name, context) => {
        const violation = branchNameViolation(name);
        if (violation !== undefined) context.addIssue({ code: "custom", message: ruleMessage(violation) });
    });

/** Empty means "the default branch", which is never sent. */
export const optionalBranchNameSchema = z
    .string()
    .trim()
    .transform((name, context) => {
        if (name === "") return undefined;
        const violation = branchNameViolation(name);
        if (violation !== undefined) context.addIssue({ code: "custom", message: ruleMessage(violation) });
        return name;
    });
