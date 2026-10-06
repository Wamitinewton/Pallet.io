import { asInviteId, asUserId } from "@/shared/domain/ids";
import { asIsoInstant } from "@/shared/domain/instant";
import type { Invite } from "../invite";
import type { InvitePreview } from "../invite-preview";
import type { InviteToken } from "../invite-token";

export function anInvite(overrides: Partial<Invite> = {}): Invite {
    return {
        id: asInviteId("6f1c2a52-3d4e-4b7a-9c1d-2e3f4a5b6c7d"),
        email: "david.kariuki@kilimalabs.co",
        role: "DEVELOPER",
        status: "PENDING",
        invitedByUserId: asUserId("user-amani"),
        sendCount: 1,
        expiresAt: asIsoInstant("2026-01-17T12:00:00Z"),
        createdAt: asIsoInstant("2026-01-14T12:00:00Z"),
        ...overrides,
    };
}

export function anInvitePreview(overrides: Partial<InvitePreview> = {}): InvitePreview {
    return {
        orgName: "Kilima Labs",
        role: "DEVELOPER",
        inviterName: "Grace Njeri",
        maskedEmail: "d***@kilimalabs.co",
        expiresAt: asIsoInstant("2026-01-17T12:00:00Z"),
        ...overrides,
    };
}

/** An invite link's token, unsigned: enough for code that reads its claims without verifying them. */
export function anInviteToken(claims: { readonly exp?: unknown; readonly orgId?: unknown } = {}): InviteToken {
    const encode = (value: object) =>
        btoa(JSON.stringify(value)).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
    const payload = {
        jti: "6f1c2a52-3d4e-4b7a-9c1d-2e3f4a5b6c7d",
        orgId: "org-kilima",
        email: "david.kariuki@kilimalabs.co",
        role: "developer",
        purpose: "invite",
        exp: Date.parse("2026-01-17T12:00:00Z") / 1000,
        ...claims,
    };
    return `${encode({ alg: "HS256" })}.${encode(payload)}.c2lnbmF0dXJl` as InviteToken;
}
