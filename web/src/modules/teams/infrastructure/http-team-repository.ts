import { asTeamId, asUserId } from "@/shared/domain/ids";
import { asIsoInstant } from "@/shared/domain/instant";
import { ROLES } from "@/shared/domain/role";
import type { OrgTeamClient } from "@/shared/infrastructure/api/clients";
import { expectNoContent, unwrap, unwrapPage } from "@/shared/infrastructure/api/envelope";
import { toPageQuery } from "@/shared/infrastructure/api/page-query";
import { z } from "zod";
import type { TeamRepository } from "../application/ports";
import type { Team, TeamMember } from "../domain/team";
import { toPageRequest } from "../domain/team-list-query";

const teamSchema = z.object({
    id: z.uuid(),
    name: z.string(),
    slug: z.string(),
    memberCount: z.number().int().nonnegative(),
    createdAt: z.iso.datetime({ offset: true }),
    updatedAt: z.iso.datetime({ offset: true }),
});

const teamMemberSchema = z.object({
    userId: z.string().min(1),
    email: z.string(),
    displayName: z.string(),
    role: z.enum(ROLES),
    joinedAt: z.iso.datetime({ offset: true }),
});

function toTeam(dto: z.output<typeof teamSchema>): Team {
    return {
        ...dto,
        id: asTeamId(dto.id),
        createdAt: asIsoInstant(dto.createdAt),
        updatedAt: asIsoInstant(dto.updatedAt),
    };
}

function toTeamMember(dto: z.output<typeof teamMemberSchema>): TeamMember {
    return { ...dto, userId: asUserId(dto.userId), joinedAt: asIsoInstant(dto.joinedAt) };
}

export function httpTeamRepository(orgTeam: OrgTeamClient): TeamRepository {
    return {
        async list(orgId, query) {
            return unwrapPage(
                await orgTeam.GET("/orgs/{orgId}/teams", {
                    params: { path: { orgId }, query: toPageQuery(toPageRequest(query)) },
                }),
                teamSchema,
                toTeam,
            );
        },

        async get(orgId, teamId) {
            return toTeam(
                unwrap(
                    await orgTeam.GET("/orgs/{orgId}/teams/{teamId}", { params: { path: { orgId, teamId } } }),
                    teamSchema,
                ),
            );
        },

        async create(orgId, { name, slug }) {
            return toTeam(
                unwrap(
                    await orgTeam.POST("/orgs/{orgId}/teams", {
                        params: { path: { orgId } },
                        body: { name, ...(slug !== undefined && { slug }) },
                    }),
                    teamSchema,
                ),
            );
        },

        async rename(orgId, teamId, { name }) {
            return toTeam(
                unwrap(
                    await orgTeam.PATCH("/orgs/{orgId}/teams/{teamId}", {
                        params: { path: { orgId, teamId } },
                        body: { name },
                    }),
                    teamSchema,
                ),
            );
        },

        async delete(orgId, teamId) {
            expectNoContent(
                await orgTeam.DELETE("/orgs/{orgId}/teams/{teamId}", { params: { path: { orgId, teamId } } }),
            );
        },

        async listMembers(orgId, teamId, query) {
            return unwrapPage(
                await orgTeam.GET("/orgs/{orgId}/teams/{teamId}/members", {
                    params: { path: { orgId, teamId }, query: toPageQuery(query) },
                }),
                teamMemberSchema,
                toTeamMember,
            );
        },

        async addMember(orgId, teamId, userId) {
            return toTeamMember(
                unwrap(
                    await orgTeam.POST("/orgs/{orgId}/teams/{teamId}/members", {
                        params: { path: { orgId, teamId } },
                        body: { userId },
                    }),
                    teamMemberSchema,
                ),
            );
        },

        async removeMember(orgId, teamId, userId) {
            expectNoContent(
                await orgTeam.DELETE("/orgs/{orgId}/teams/{teamId}/members/{userId}", {
                    params: { path: { orgId, teamId, userId } },
                }),
            );
        },
    };
}
