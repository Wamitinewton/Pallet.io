import { asOrgId, asUserId } from "@/shared/domain/ids";
import { asIsoInstant } from "@/shared/domain/instant";
import { ORG_KINDS } from "@/shared/domain/org-kind";
import { ROLES } from "@/shared/domain/role";
import type { OrgTeamClient } from "@/shared/infrastructure/api/clients";
import { expectNoContent, unwrap, unwrapPage } from "@/shared/infrastructure/api/envelope";
import { toPageQuery } from "@/shared/infrastructure/api/page-query";
import { z } from "zod";
import type { OrganizationRepository } from "../application/ports";
import { ORG_STATUSES, type Organization, type OrgSummary } from "../domain/organization";

const count = z.number().int().nonnegative();

const orgSummarySchema = z.object({
    orgId: z.string().min(1),
    name: z.string(),
    slug: z.string(),
    kind: z.enum(ORG_KINDS),
    myRole: z.enum(ROLES),
});

const organizationSchema = z.object({
    orgId: z.string().min(1),
    name: z.string(),
    slug: z.string(),
    kind: z.enum(ORG_KINDS),
    status: z.enum(ORG_STATUSES),
    ownerUserId: z.string().min(1),
    createdAt: z.iso.datetime({ offset: true }),
    counts: z.object({ members: count, teams: count, apps: count }),
});

function toSummary(dto: z.output<typeof orgSummarySchema>): OrgSummary {
    return { ...dto, orgId: asOrgId(dto.orgId) };
}

function toOrganization(dto: z.output<typeof organizationSchema>): Organization {
    return {
        ...dto,
        orgId: asOrgId(dto.orgId),
        ownerUserId: asUserId(dto.ownerUserId),
        createdAt: asIsoInstant(dto.createdAt),
    };
}

export function httpOrganizationRepository(orgTeam: OrgTeamClient): OrganizationRepository {
    return {
        async listMine(request) {
            return unwrapPage(
                await orgTeam.GET("/orgs", { params: { query: toPageQuery(request) } }),
                orgSummarySchema,
                toSummary,
            );
        },

        async get(orgId) {
            return toOrganization(
                unwrap(await orgTeam.GET("/orgs/{orgId}", { params: { path: { orgId } } }), organizationSchema),
            );
        },

        async create({ name, slug }) {
            return toOrganization(
                unwrap(
                    await orgTeam.POST("/orgs", { body: { name, ...(slug !== undefined && { slug }) } }),
                    organizationSchema,
                ),
            );
        },

        async rename(orgId, { name }) {
            return toOrganization(
                unwrap(
                    await orgTeam.PATCH("/orgs/{orgId}", { params: { path: { orgId } }, body: { name } }),
                    organizationSchema,
                ),
            );
        },

        async delete(orgId, confirmSlug) {
            expectNoContent(
                await orgTeam.DELETE("/orgs/{orgId}", {
                    params: { path: { orgId }, header: { "X-Confirm-Slug": confirmSlug } },
                }),
            );
        },
    };
}
