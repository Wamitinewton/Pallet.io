import type { Clock } from "@/shared/domain/clock";
import {
    makeAcceptAsSignedIn,
    makeAcceptWithNewAccount,
    type AcceptAsSignedIn,
    type AcceptWithNewAccount,
} from "./accept-invite";
import { makeCreateInvite, type CreateInvite } from "./create-invite";
import { makeListInvites, type ListInvites } from "./list-invites";
import type { InviteAcceptanceRepository, InviteRepository } from "./ports";
import { makePreviewInvite, type PreviewInvite } from "./preview-invite";
import {
    makeResendInvite,
    makeResendPendingInvite,
    type ResendInvite,
    type ResendPendingInvite,
} from "./resend-invite";
import { makeRevokeInvite, type RevokeInvite } from "./revoke-invite";

export interface InviteUseCases {
    readonly listInvites: ListInvites;
    readonly createInvite: CreateInvite;
    readonly resendInvite: ResendInvite;
    readonly resendPendingInvite: ResendPendingInvite;
    readonly revokeInvite: RevokeInvite;
    readonly previewInvite: PreviewInvite;
    readonly acceptWithNewAccount: AcceptWithNewAccount;
    readonly acceptAsSignedIn: AcceptAsSignedIn;
}

export interface InviteDependencies {
    readonly invites: InviteRepository;
    readonly acceptance: InviteAcceptanceRepository;
    readonly clock: Clock;
}

export function makeInviteUseCases({ invites, acceptance, clock }: InviteDependencies): InviteUseCases {
    return {
        listInvites: makeListInvites(invites),
        createInvite: makeCreateInvite(invites),
        resendInvite: makeResendInvite(invites),
        resendPendingInvite: makeResendPendingInvite(invites),
        revokeInvite: makeRevokeInvite(invites),
        previewInvite: makePreviewInvite({ acceptance, clock }),
        acceptWithNewAccount: makeAcceptWithNewAccount({ acceptance, clock }),
        acceptAsSignedIn: makeAcceptAsSignedIn({ acceptance, clock }),
    };
}
