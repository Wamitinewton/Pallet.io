import { ApiError } from "@/shared/domain/errors";
import type { InviteId, OrgId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import type { NewInvite } from "../../domain/create-invite";
import type { Invite } from "../../domain/invite";
import {
    ACCOUNT_EXISTS,
    INVITE_ALREADY_CONSUMED,
    INVITE_NOT_FOUND,
    INVITE_NOT_PENDING,
} from "../../domain/invite-errors";
import type { InviteListQuery } from "../../domain/invite-list-query";
import type { InvitePreview } from "../../domain/invite-preview";
import type { InviteToken } from "../../domain/invite-token";
import { anInvite, anInvitePreview } from "../../domain/testing/fixtures";
import type { InviteAcceptanceRepository, InviteRepository } from "../ports";

const refusal = (status: number, code: string) => new ApiError(status, code, code, [], {}, undefined);

export class InMemoryInviteRepository implements InviteRepository {
    readonly listQueries: { readonly orgId: OrgId; readonly query: InviteListQuery }[] = [];
    readonly created: { readonly orgId: OrgId; readonly invite: NewInvite }[] = [];
    readonly resent: { readonly orgId: OrgId; readonly inviteId: InviteId }[] = [];
    readonly revoked: { readonly orgId: OrgId; readonly inviteId: InviteId }[] = [];
    /** Every list request fails with this while set. */
    listFailure: Error | undefined;

    constructor(public invites: Invite[] = []) {}

    list(orgId: OrgId, query: InviteListQuery): Promise<Page<Invite>> {
        this.listQueries.push({ orgId, query });
        if (this.listFailure !== undefined) return Promise.reject(this.listFailure);
        const matching = this.invites.filter((invite) => query.status === null || invite.status === query.status);
        const items = matching.slice(query.page * query.size, (query.page + 1) * query.size);
        const totalPages = Math.ceil(matching.length / query.size);
        return Promise.resolve({
            items,
            page: query.page,
            size: query.size,
            totalItems: matching.length,
            totalPages,
            isFirst: query.page === 0,
            isLast: query.page >= totalPages - 1,
        });
    }

    create(orgId: OrgId, invite: NewInvite): Promise<Invite> {
        this.created.push({ orgId, invite });
        const created = anInvite({ ...invite, sendCount: 1 });
        this.invites = [created, ...this.invites];
        return Promise.resolve(created);
    }

    resend(orgId: OrgId, inviteId: InviteId): Promise<Invite> {
        this.resent.push({ orgId, inviteId });
        const invite = this.invites.find((candidate) => candidate.id === inviteId);
        if (invite === undefined) return Promise.reject(refusal(404, INVITE_NOT_FOUND));
        if (invite.status !== "PENDING") return Promise.reject(refusal(409, INVITE_NOT_PENDING));
        const resent = { ...invite, sendCount: invite.sendCount + 1 };
        this.replace(resent);
        return Promise.resolve(resent);
    }

    /** As the backend does, an invite that is no longer pending reads as not found. */
    revoke(orgId: OrgId, inviteId: InviteId): Promise<void> {
        this.revoked.push({ orgId, inviteId });
        const invite = this.invites.find((candidate) => candidate.id === inviteId);
        if (invite?.status !== "PENDING") return Promise.reject(refusal(404, INVITE_NOT_FOUND));
        this.replace({ ...invite, status: "REVOKED" });
        return Promise.resolve();
    }

    private replace(invite: Invite): void {
        this.invites = this.invites.map((candidate) => (candidate.id === invite.id ? invite : candidate));
    }
}

/** One invite as its link sees it: accepting consumes it, and an address that already has an account can't create one. */
export class InMemoryInviteAcceptanceRepository implements InviteAcceptanceRepository {
    readonly previewed: InviteToken[] = [];
    readonly newAccounts: { readonly token: InviteToken; readonly password: string }[] = [];
    readonly signedInAcceptances: InviteToken[] = [];
    consumed = false;
    /** Every request fails with this while set. */
    failure: Error | undefined;

    constructor(
        public invitation: InvitePreview = anInvitePreview(),
        public accountExists = false,
    ) {}

    preview(token: InviteToken): Promise<InvitePreview> {
        this.previewed.push(token);
        return this.failure === undefined ? Promise.resolve(this.invitation) : Promise.reject(this.failure);
    }

    acceptWithNewAccount(token: InviteToken, password: string): Promise<void> {
        this.newAccounts.push({ token, password });
        if (this.accountExists) return Promise.reject(refusal(409, ACCOUNT_EXISTS));
        return this.consume();
    }

    acceptAsSignedIn(token: InviteToken): Promise<void> {
        this.signedInAcceptances.push(token);
        return this.consume();
    }

    private consume(): Promise<void> {
        if (this.failure !== undefined) return Promise.reject(this.failure);
        if (this.consumed) return Promise.reject(refusal(409, INVITE_ALREADY_CONSUMED));
        this.consumed = true;
        return Promise.resolve();
    }
}
