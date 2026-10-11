import { ApiError } from "@/shared/domain/errors";
import { asAppId, asInstallationId, asRepoId, asUserId } from "@/shared/domain/ids";
import { asIsoInstant } from "@/shared/domain/instant";
import type { GitIntegrationClient } from "@/shared/infrastructure/api/clients";
import { expectNoContent, unwrap } from "@/shared/infrastructure/api/envelope";
import type { components } from "@/shared/infrastructure/api/generated/git-integration";
import { IDEMPOTENCY_KEY_HEADER } from "@/shared/infrastructure/api/headers";
import { z } from "zod";
import type { RepoLinkRepository } from "../application/ports";
import type { BuildSettingsChange } from "../domain/build-settings";
import type { ManualBuild } from "../domain/manual-build";
import {
    DISCONNECT_REASONS,
    isRepoLinkWarning,
    VERIFIER_PERMISSIONS,
    type Connection,
    type RepoLink,
} from "../domain/repo-link";
import { REPO_LINK_NOT_FOUND } from "../domain/repo-link-errors";
import { REPOSITORY_ROOT } from "../domain/root-directory";

type PatchRepoLinkRequest = components["schemas"]["PatchRepoLinkRequest"];

const instantSchema = z.iso.datetime({ offset: true });
const githubIdSchema = z.number().int().positive();

const headSchema = z.object({
    branch: z.string().min(1),
    sha: z.string().min(1),
    advancedAt: instantSchema,
    message: z.string().nullish(),
    author: z.string().nullish(),
});

const verificationSchema = z.object({
    verifiedByUserId: z.string().min(1),
    githubLogin: z.string().min(1),
    permission: z.enum(VERIFIER_PERMISSIONS),
    accessVerifiedAt: instantSchema,
    accessCheckedAt: instantSchema,
});

const linkFields = {
    appId: z.uuid(),
    installationId: githubIdSchema,
    repoId: githubIdSchema,
    repoFullName: z.string().min(1),
    productionBranch: z.string().min(1),
    rootDirectory: z.string().nullish(),
    autoDeploy: z.boolean(),
    warnings: z.array(z.string()),
    lastAcceptedHead: headSchema.nullish(),
    verification: verificationSchema.nullish(),
    version: z.number().int().nonnegative(),
    createdAt: instantSchema,
    updatedAt: instantSchema,
};

/** A disconnected link always says why and when; an active one carries neither. */
const repoLinkSchema = z.discriminatedUnion("status", [
    z.object({ ...linkFields, status: z.literal("ACTIVE") }),
    z.object({
        ...linkFields,
        status: z.literal("DISCONNECTED"),
        disconnectReason: z.enum(DISCONNECT_REASONS),
        disconnectedAt: instantSchema,
    }),
]);

type RepoLinkDto = z.output<typeof repoLinkSchema>;

const manualBuildSchema = z.object({
    eventId: z.uuid(),
    commitSha: z.string().min(1),
    branch: z.string().min(1),
});

function toConnection(dto: RepoLinkDto): Connection {
    return dto.status === "ACTIVE"
        ? { status: "ACTIVE" }
        : { status: "DISCONNECTED", reason: dto.disconnectReason, disconnectedAt: asIsoInstant(dto.disconnectedAt) };
}

/** A warning this client doesn't know yet is advisory, so it is left out rather than failing the read. */
function toRepoLink(dto: RepoLinkDto): RepoLink {
    const { lastAcceptedHead: head, verification } = dto;
    return {
        appId: asAppId(dto.appId),
        installationId: asInstallationId(dto.installationId),
        repoId: asRepoId(dto.repoId),
        repoFullName: dto.repoFullName,
        productionBranch: dto.productionBranch,
        rootDirectory: dto.rootDirectory ?? REPOSITORY_ROOT,
        autoDeploy: dto.autoDeploy,
        connection: toConnection(dto),
        warnings: dto.warnings.filter(isRepoLinkWarning),
        lastAcceptedHead:
            head == null
                ? null
                : {
                      branch: head.branch,
                      sha: head.sha,
                      advancedAt: asIsoInstant(head.advancedAt),
                      message: head.message ?? null,
                      author: head.author ?? null,
                  },
        verification:
            verification == null
                ? null
                : {
                      ...verification,
                      verifiedByUserId: asUserId(verification.verifiedByUserId),
                      accessVerifiedAt: asIsoInstant(verification.accessVerifiedAt),
                      accessCheckedAt: asIsoInstant(verification.accessCheckedAt),
                  },
        version: dto.version,
        createdAt: asIsoInstant(dto.createdAt),
        updatedAt: asIsoInstant(dto.updatedAt),
    };
}

/** Exactly the changed fields and the version: the backend refuses any other field, ids included. */
function toPatchBody({ productionBranch, rootDirectory, autoDeploy }: BuildSettingsChange, version: number) {
    const body: PatchRepoLinkRequest = {
        ...(productionBranch !== undefined && { productionBranch }),
        ...(rootDirectory !== undefined && { rootDirectory }),
        ...(autoDeploy !== undefined && { autoDeploy }),
        version,
    };
    return body;
}

function isNotLinked(error: unknown): boolean {
    return error instanceof ApiError && error.is(REPO_LINK_NOT_FOUND);
}

export function httpRepoLinkRepository(gitIntegration: GitIntegrationClient): RepoLinkRepository {
    return {
        async find(orgId, appId) {
            try {
                return toRepoLink(
                    unwrap(
                        await gitIntegration.GET("/orgs/{orgId}/apps/{appId}/repo-link", {
                            params: { path: { orgId, appId } },
                        }),
                        repoLinkSchema,
                    ),
                );
            } catch (error) {
                if (isNotLinked(error)) return null;
                throw error;
            }
        },

        async link(orgId, appId, { installationId, repoId, settings, deployNow }) {
            const { productionBranch, rootDirectory, autoDeploy } = settings;
            return toRepoLink(
                unwrap(
                    await gitIntegration.PUT("/orgs/{orgId}/apps/{appId}/repo-link", {
                        params: { path: { orgId, appId } },
                        body: {
                            installationId,
                            repoId,
                            productionBranch,
                            ...(rootDirectory !== REPOSITORY_ROOT && { rootDirectory }),
                            autoDeploy,
                            deployNow,
                        },
                    }),
                    repoLinkSchema,
                ),
            );
        },

        async tune(orgId, appId, change, version) {
            return toRepoLink(
                unwrap(
                    await gitIntegration.PATCH("/orgs/{orgId}/apps/{appId}/repo-link", {
                        params: { path: { orgId, appId } },
                        body: toPatchBody(change, version),
                    }),
                    repoLinkSchema,
                ),
            );
        },

        async takeOverVerification(orgId, appId) {
            return toRepoLink(
                unwrap(
                    await gitIntegration.POST("/orgs/{orgId}/apps/{appId}/repo-link/verification", {
                        params: { path: { orgId, appId } },
                    }),
                    repoLinkSchema,
                ),
            );
        },

        async disconnect(orgId, appId) {
            expectNoContent(
                await gitIntegration.DELETE("/orgs/{orgId}/apps/{appId}/repo-link", {
                    params: { path: { orgId, appId } },
                }),
            );
        },

        async requestBuild(orgId, appId, request, idempotencyKey): Promise<ManualBuild> {
            return unwrap(
                await gitIntegration.POST("/orgs/{orgId}/apps/{appId}/repo-link/builds", {
                    params: { path: { orgId, appId }, header: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey } },
                    body: request,
                }),
                manualBuildSchema,
            );
        },
    };
}
