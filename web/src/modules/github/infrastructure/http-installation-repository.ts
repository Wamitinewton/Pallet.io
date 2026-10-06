import { UnexpectedResponseError } from "@/shared/domain/errors";
import { asInstallationId, asUserId } from "@/shared/domain/ids";
import { asIsoInstant } from "@/shared/domain/instant";
import type { GitIntegrationClient } from "@/shared/infrastructure/api/clients";
import { expectNoContent, unwrap, unwrapPage } from "@/shared/infrastructure/api/envelope";
import { CORRELATION_ID_HEADER } from "@/shared/infrastructure/api/headers";
import { toPageQuery } from "@/shared/infrastructure/api/page-query";
import { z } from "zod";
import type { InstallationRepository } from "../application/ports";
import { INSTALLATION_STATUSES, type InstallationLink } from "../domain/installation";
import { accountSchema, githubUrlSchema, instantSchema } from "./github-dto";

const installSessionSchema = z.object({ installUrl: githubUrlSchema, expiresAt: instantSchema });

const installationLinkSchema = z.object({
    ...accountSchema,
    status: z.enum(INSTALLATION_STATUSES),
    linkedByUserId: z.string().min(1),
    linkedAt: instantSchema,
});

function toInstallationLink(dto: z.output<typeof installationLinkSchema>): InstallationLink {
    return {
        ...dto,
        installationId: asInstallationId(dto.installationId),
        linkedByUserId: asUserId(dto.linkedByUserId),
        linkedAt: asIsoInstant(dto.linkedAt),
    };
}

/** `201` links it now, `200` says it was linked already; anything else broke the contract. */
function alreadyLinked(response: Response): boolean {
    if (response.status === 200 || response.status === 201) return response.status === 200;
    throw new UnexpectedResponseError(
        response.status,
        `${String(response.status)} response to linking an installation: expected 200 or 201`,
        response.headers.get(CORRELATION_ID_HEADER) ?? undefined,
    );
}

export function httpInstallationRepository(gitIntegration: GitIntegrationClient): InstallationRepository {
    return {
        async startInstall(orgId) {
            const { installUrl, expiresAt } = unwrap(
                await gitIntegration.POST("/orgs/{orgId}/github/install-sessions", { params: { path: { orgId } } }),
                installSessionSchema,
            );
            return { url: installUrl, expiresAt: asIsoInstant(expiresAt) };
        },

        async link(orgId, { installationId, grant }) {
            const result = await gitIntegration.POST("/orgs/{orgId}/github/installations", {
                params: { path: { orgId } },
                body: { installationId, ...grant },
            });
            return {
                alreadyLinked: alreadyLinked(result.response),
                link: toInstallationLink(unwrap(result, installationLinkSchema)),
            };
        },

        async list(orgId, page) {
            return unwrapPage(
                await gitIntegration.GET("/orgs/{orgId}/github/installations", {
                    params: { path: { orgId }, query: toPageQuery(page) },
                }),
                installationLinkSchema,
                toInstallationLink,
            );
        },

        async unlink(orgId, installationId) {
            expectNoContent(
                await gitIntegration.DELETE("/orgs/{orgId}/github/installations/{installationId}", {
                    params: { path: { orgId, installationId } },
                }),
            );
        },
    };
}
