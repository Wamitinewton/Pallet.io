import { asAppId, asTeamId } from "@/shared/domain/ids";
import { asIsoInstant } from "@/shared/domain/instant";
import type { OrgTeamClient } from "@/shared/infrastructure/api/clients";
import { expectNoContent, unwrap, unwrapPage } from "@/shared/infrastructure/api/envelope";
import type { components, paths } from "@/shared/infrastructure/api/generated/org-team";
import { toPageQuery } from "@/shared/infrastructure/api/page-query";
import { z } from "zod";
import type { AppRepository } from "../application/ports";
import type { App } from "../domain/app";
import { toPageRequest, type AppListQuery, type AppTeamFilter } from "../domain/app-list-query";
import { CLOUD_PROVIDERS } from "../domain/region-catalog";
import type { AppChange } from "../domain/update-app";

type UpdateAppRequest = components["schemas"]["UpdateAppRequest"];
type AppListParams = NonNullable<paths["/orgs/{orgId}/apps"]["get"]["parameters"]["query"]>;
type AppTeamParams = Pick<AppListParams, "teamId" | "unassigned">;

const appSchema = z.object({
    id: z.uuid(),
    name: z.string(),
    slug: z.string(),
    cloudProvider: z.enum(CLOUD_PROVIDERS),
    region: z.string().min(1),
    teamId: z.uuid().nullish(),
    createdAt: z.iso.datetime({ offset: true }),
    updatedAt: z.iso.datetime({ offset: true }),
});

function toApp(dto: z.output<typeof appSchema>): App {
    return {
        ...dto,
        id: asAppId(dto.id),
        teamId: dto.teamId == null ? null : asTeamId(dto.teamId),
        createdAt: asIsoInstant(dto.createdAt),
        updatedAt: asIsoInstant(dto.updatedAt),
    };
}

function toTeamParams(team: AppTeamFilter): AppTeamParams {
    switch (team.kind) {
        case "any":
            return {};
        case "unassigned":
            return { unassigned: true };
        case "team":
            return { teamId: team.teamId };
    }
}

function toListParams(query: AppListQuery): AppListParams {
    return {
        ...toPageQuery(toPageRequest(query)),
        ...toTeamParams(query.team),
        ...(query.cloudProvider !== null && { cloudProvider: query.cloudProvider }),
        ...(query.q !== "" && { q: query.q }),
    };
}

function toUpdateBody({ name, teamId }: AppChange): UpdateAppRequest {
    return {
        ...(name !== undefined && { name }),
        ...(teamId !== undefined && { teamId }),
    };
}

export function httpAppRepository(orgTeam: OrgTeamClient): AppRepository {
    return {
        async list(orgId, query) {
            return unwrapPage(
                await orgTeam.GET("/orgs/{orgId}/apps", {
                    params: { path: { orgId }, query: toListParams(query) },
                }),
                appSchema,
                toApp,
            );
        },

        async get(orgId, appId) {
            return toApp(
                unwrap(
                    await orgTeam.GET("/orgs/{orgId}/apps/{appId}", { params: { path: { orgId, appId } } }),
                    appSchema,
                ),
            );
        },

        async create(orgId, { name, slug, cloudProvider, region, teamId }) {
            return toApp(
                unwrap(
                    await orgTeam.POST("/orgs/{orgId}/apps", {
                        params: { path: { orgId } },
                        body: {
                            name,
                            cloudProvider,
                            region,
                            ...(slug !== undefined && { slug }),
                            ...(teamId !== null && { teamId }),
                        },
                    }),
                    appSchema,
                ),
            );
        },

        async update(orgId, appId, change) {
            return toApp(
                unwrap(
                    await orgTeam.PATCH("/orgs/{orgId}/apps/{appId}", {
                        params: { path: { orgId, appId } },
                        body: toUpdateBody(change),
                    }),
                    appSchema,
                ),
            );
        },

        async delete(orgId, appId) {
            expectNoContent(await orgTeam.DELETE("/orgs/{orgId}/apps/{appId}", { params: { path: { orgId, appId } } }));
        },
    };
}
