import type { InstallationId, OrgId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import type { PickerRepository, RepositoryQuery } from "../domain/picker-repository";
import type { RepositoryPickerRepository } from "./ports";

export type ListLinkableRepositories = (
    orgId: OrgId,
    installationId: InstallationId,
    query: RepositoryQuery,
) => Promise<Page<PickerRepository>>;

export function makeListLinkableRepositories(repositories: RepositoryPickerRepository): ListLinkableRepositories {
    return (orgId, installationId, query) => repositories.list(orgId, installationId, query);
}
