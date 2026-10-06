import { asUserId } from "@/shared/domain/ids";
import { asIsoInstant } from "@/shared/domain/instant";
import { ROLES } from "@/shared/domain/role";
import type { OrgTeamClient } from "@/shared/infrastructure/api/clients";
import { expectNoContent, unwrap, unwrapPage } from "@/shared/infrastructure/api/envelope";
import { toPageQuery } from "@/shared/infrastructure/api/page-query";
import { z } from "zod";
import type { MemberRepository } from "../application/ports";
import { MEMBER_STATUSES, type Member } from "../domain/member";
import { toPageRequest, type MemberListQuery } from "../domain/member-list-query";

const memberSchema = z.object({
    userId: z.string().min(1),
    email: z.string(),
    displayName: z.string(),
    role: z.enum(ROLES),
    status: z.enum(MEMBER_STATUSES),
    joinedAt: z.iso.datetime({ offset: true }),
});

function toMember(dto: z.output<typeof memberSchema>): Member {
    return { ...dto, userId: asUserId(dto.userId), joinedAt: asIsoInstant(dto.joinedAt) };
}

function listParams(query: MemberListQuery) {
    return {
        ...toPageQuery(toPageRequest(query)),
        status: query.status,
        ...(query.role !== null && { role: query.role }),
        ...(query.q !== "" && { q: query.q }),
    };
}

export function httpMemberRepository(orgTeam: OrgTeamClient): MemberRepository {
    return {
        async list(orgId, query) {
            return unwrapPage(
                await orgTeam.GET("/orgs/{orgId}/members", {
                    params: { path: { orgId }, query: listParams(query) },
                }),
                memberSchema,
                toMember,
            );
        },

        async getMine(orgId) {
            return toMember(
                unwrap(await orgTeam.GET("/orgs/{orgId}/members/me", { params: { path: { orgId } } }), memberSchema),
            );
        },

        async changeRole(orgId, userId, role) {
            return toMember(
                unwrap(
                    await orgTeam.PATCH("/orgs/{orgId}/members/{userId}", {
                        params: { path: { orgId, userId } },
                        body: { role },
                    }),
                    memberSchema,
                ),
            );
        },

        async remove(orgId, userId) {
            expectNoContent(
                await orgTeam.DELETE("/orgs/{orgId}/members/{userId}", { params: { path: { orgId, userId } } }),
            );
        },

        async transferOwnership(orgId, userId) {
            return toMember(
                unwrap(
                    await orgTeam.POST("/orgs/{orgId}/members/{userId}/transfer-ownership", {
                        params: { path: { orgId, userId } },
                    }),
                    memberSchema,
                ),
            );
        },
    };
}
