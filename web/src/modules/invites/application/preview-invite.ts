import type { Clock } from "@/shared/domain/clock";
import { ApiError } from "@/shared/domain/errors";
import { INVALID_TOKEN, INVITE_NO_LONGER_VALID } from "../domain/invite-errors";
import { unavailableReason, type InvitePreview, type UnavailableReason } from "../domain/invite-preview";
import { parseInviteToken, readTokenHints } from "../domain/invite-token";
import type { InviteAcceptanceRepository } from "./ports";

export type InvitePreviewOutcome =
    | { readonly kind: "open"; readonly preview: InvitePreview }
    | { readonly kind: "unavailable"; readonly reason: UnavailableReason };

/** A dead link is an answer the page draws, not a failure; anything else unexpected is thrown. */
export type PreviewInvite = (token: string) => Promise<InvitePreviewOutcome>;

const UNAVAILABLE: readonly string[] = [INVALID_TOKEN, INVITE_NO_LONGER_VALID];

export interface PreviewInviteDependencies {
    readonly acceptance: InviteAcceptanceRepository;
    readonly clock: Clock;
}

export function makePreviewInvite({ acceptance, clock }: PreviewInviteDependencies): PreviewInvite {
    return async (value) => {
        const token = parseInviteToken(value);
        if (token === undefined) return { kind: "unavailable", reason: "withdrawn" };
        try {
            return { kind: "open", preview: await acceptance.preview(token) };
        } catch (error) {
            if (!(error instanceof ApiError && error.isClientError() && UNAVAILABLE.includes(error.code))) throw error;
            return { kind: "unavailable", reason: unavailableReason(readTokenHints(token).expiresAt, clock.now()) };
        }
    };
}
