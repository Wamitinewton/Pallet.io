import { asRepoId } from "@/shared/domain/ids";
import type { GitIntegrationClient } from "@/shared/infrastructure/api/clients";
import { unwrapPage } from "@/shared/infrastructure/api/envelope";
import { toPageQuery } from "@/shared/infrastructure/api/page-query";
import { z } from "zod";
import type { RepositoryPickerRepository } from "../application/ports";
import { REPOSITORY_PERMISSIONS, type PickerRepository } from "../domain/picker-repository";

const pickerRepositorySchema = z.object({
    repoId: z.number().int().positive(),
    fullName: z.string().min(1),
    defaultBranch: z.string().min(1),
    private: z.boolean(),
    archived: z.boolean(),
    permission: z.enum(REPOSITORY_PERMISSIONS).nullish(),
    linkable: z.boolean(),
});

function toPickerRepository(dto: z.output<typeof pickerRepositorySchema>): PickerRepository {
    return {
        repoId: asRepoId(dto.repoId),
        fullName: dto.fullName,
        defaultBranch: dto.defaultBranch,
        isPrivate: dto.private,
        archived: dto.archived,
        permission: dto.permission ?? null,
        linkable: dto.linkable,
    };
}

export function httpRepositoryPickerRepository(gitIntegration: GitIntegrationClient): RepositoryPickerRepository {
    return {
        async list(orgId, installationId, { q, page, size }) {
            return unwrapPage(
                await gitIntegration.GET("/orgs/{orgId}/github/installations/{installationId}/repositories", {
                    params: {
                        path: { orgId, installationId },
                        query: { ...toPageQuery({ page, size }), ...(q !== "" && { q }) },
                    },
                }),
                pickerRepositorySchema,
                toPickerRepository,
            );
        },
    };
}
