import type { RepoId } from "@/shared/domain/ids";

/** GitHub's repository permissions, lowest first. */
export const REPOSITORY_PERMISSIONS = ["pull", "triage", "push", "maintain", "admin"] as const;

export type RepositoryPermission = (typeof REPOSITORY_PERMISSIONS)[number];

/** The least a person needs on GitHub to link a repository or vouch for one. */
export const LINKING_PERMISSION: RepositoryPermission = "push";

/** A repository in the picker: one the caller's own GitHub token reaches through the installation. */
export interface PickerRepository {
    readonly repoId: RepoId;
    readonly fullName: string;
    readonly defaultBranch: string;
    readonly isPrivate: boolean;
    readonly archived: boolean;
    /** The caller's own highest permission, or null for none. */
    readonly permission: RepositoryPermission | null;
    /** The backend's verdict on whether linking would be accepted: the authority, whatever this client works out. */
    readonly linkable: boolean;
}

/** `unavailable` is the backend refusing a repository for a reason it didn't say. */
export type BlockedReason = "archived" | "permission" | "unavailable";

export type Linkability = { readonly linkable: true } | { readonly linkable: false; readonly reason: BlockedReason };

export function permissionRank(permission: RepositoryPermission | null): number {
    return permission === null ? -1 : REPOSITORY_PERMISSIONS.indexOf(permission);
}

export function canPush(permission: RepositoryPermission | null): boolean {
    return permissionRank(permission) >= permissionRank(LINKING_PERMISSION);
}

function blockedReason({ archived, permission }: PickerRepository): BlockedReason {
    if (archived) return "archived";
    return canPush(permission) ? "unavailable" : "permission";
}

/** Why a row is held back. Archived is said first: no permission makes an archived repository linkable. */
export function linkabilityOf(repository: PickerRepository): Linkability {
    return repository.linkable ? { linkable: true } : { linkable: false, reason: blockedReason(repository) };
}

export const REPOSITORY_SEARCH_MAX_LENGTH = 100;
export const REPOSITORY_PAGE_SIZE = 20;

/** One page of the picker as it is asked of the backend: `q` filters by name prefix. */
export interface RepositoryQuery {
    readonly q: string;
    readonly page: number;
    readonly size: number;
}

/** From the address bar, where pages count from one and any value may be stale or hand-edited. */
export function repositoryQuery(q: string, page: number): RepositoryQuery {
    return {
        q: q.trim().slice(0, REPOSITORY_SEARCH_MAX_LENGTH),
        page: Number.isInteger(page) && page > 1 ? page - 1 : 0,
        size: REPOSITORY_PAGE_SIZE,
    };
}
