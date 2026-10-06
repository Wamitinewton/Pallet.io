import { asInviteId, asUserId } from "@/shared/domain/ids";
import { asIsoInstant } from "@/shared/domain/instant";
import { ROLES } from "@/shared/domain/role";
import type { OrgTeamClient } from "@/shared/infrastructure/api/clients";
import { expectNoContent, unwrap, unwrapPage } from "@/shared/infrastructure/api/envelope";
import { toPageQuery } from "@/shared/infrastructure/api/page-query";
import { z } from "zod";
import type { InviteRepository } from "../application/ports";
import { INVITE_STATUSES, type Invite } from "../domain/invite";
import { toPageRequest, type InviteListQuery } from "../domain/invite-list-query";

const inviteSchema = z.object({
    id: z.uuid(),
    email: z.string(),
    role: z.enum(ROLES),
    status: z.enum(INVITE_STATUSES),
    invitedByUserId: z.string().min(1),
    sendCount: z.int().nonnegative(),
    expiresAt: z.iso.datetime({ offset: true }),
    createdAt: z.iso.datetime({ offset: true }),
});

function toInvite(dto: z.output<typeof inviteSchema>): Invite {
    return {
        ...dto,
        id: asInviteId(dto.id),
        invitedByUserId: asUserId(dto.invitedByUserId),
        expiresAt: asIsoInstant(dto.expiresAt),
        createdAt: asIsoInstant(dto.createdAt),
    };
}

function listParams(query: InviteListQuery) {
    return {
        ...toPageQuery(toPageRequest(query)),
        ...(query.status !== null && { status: query.status }),
    };
}

export function httpInviteRepository(orgTeam: OrgTeamClient): InviteRepository {
    return {
        async list(orgId, query) {
            return unwrapPage(
                await orgTeam.GET("/orgs/{orgId}/invites", {
                    params: { path: { orgId }, query: listParams(query) },
                }),
                inviteSchema,
                toInvite,
            );
        },

        async create(orgId, invite) {
            return toInvite(
                unwrap(
                    await orgTeam.POST("/orgs/{orgId}/invites", { params: { path: { orgId } }, body: invite }),
                    inviteSchema,
                ),
            );
        },

        async resend(orgId, inviteId) {
            return toInvite(
                unwrap(
                    await orgTeam.POST("/orgs/{orgId}/invites/{inviteId}/resend", {
                        params: { path: { orgId, inviteId } },
                    }),
                    inviteSchema,
                ),
            );
        },

        async revoke(orgId, inviteId) {
            expectNoContent(
                await orgTeam.DELETE("/orgs/{orgId}/invites/{inviteId}", { params: { path: { orgId, inviteId } } }),
            );
        },
    };
}
