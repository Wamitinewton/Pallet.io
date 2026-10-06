import { asOrgId, type Brand, type OrgId } from "@/shared/domain/ids";
import { z } from "zod";

export type InviteToken = Brand<string, "InviteToken">;

/** org-team-service signs invite tokens as compact JWTs; the bound only keeps junk out of a request. */
const INVITE_TOKEN_PATTERN = /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/;
export const MAX_INVITE_TOKEN_LENGTH = 2048;

/** The token arrives in the URL, so it is untrusted: anything that can't be an invite token reads as none. */
export function parseInviteToken(value: unknown): InviteToken | undefined {
    return typeof value === "string" && value.length <= MAX_INVITE_TOKEN_LENGTH && INVITE_TOKEN_PATTERN.test(value)
        ? (value as InviteToken)
        : undefined;
}

/**
 * What the token says about itself, read without verifying its signature. It only picks which copy to show
 * and recognizes the organization once joined; the backend verifies the token on every request.
 */
export interface InviteTokenHints {
    readonly expiresAt: Date | undefined;
    readonly orgId: OrgId | undefined;
}

const claimsSchema = z.object({
    exp: z.number().int().positive().optional().catch(undefined),
    orgId: z.string().min(1).optional().catch(undefined),
});

function decodePayload(token: InviteToken): unknown {
    const base64 = (token.split(".")[1] ?? "").replaceAll("-", "+").replaceAll("_", "/");
    try {
        const bytes = Uint8Array.from(atob(base64), (character) => character.charCodeAt(0));
        return JSON.parse(new TextDecoder().decode(bytes));
    } catch {
        return undefined;
    }
}

export function readTokenHints(token: InviteToken): InviteTokenHints {
    const claims = claimsSchema.safeParse(decodePayload(token)).data ?? {};
    return {
        expiresAt: claims.exp === undefined ? undefined : new Date(claims.exp * 1000),
        orgId: claims.orgId === undefined ? undefined : asOrgId(claims.orgId),
    };
}
