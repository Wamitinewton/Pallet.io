import { rankAttention, type AppRef, type AttentionItem } from "../domain/attention";

/** An app's repository as far as it is known; `unknown` while its link loads or when it couldn't be read. */
export type RepositoryState<Reason> =
    | { readonly kind: "unknown" }
    | { readonly kind: "unlinked" }
    | { readonly kind: "connected"; readonly repoFullName: string; readonly archived: boolean }
    | { readonly kind: "disconnected"; readonly repoFullName: string; readonly reason: Reason };

export interface RecentAppState<Reason> {
    readonly app: AppRef;
    readonly repository: RepositoryState<Reason>;
}

export interface InstallationState {
    readonly accountLogin: string;
    readonly suspended: boolean;
}

/** Undefined means not loaded, failed, or not the caller's to read: nothing is said about it either way. */
export interface AttentionInput<Reason> {
    /** The newest apps, newest first. */
    readonly apps: readonly RecentAppState<Reason>[] | undefined;
    readonly installations: readonly InstallationState[] | undefined;
    readonly canManageInstallations: boolean;
    readonly pendingInvites: number | undefined;
    readonly unreadNotifications: number | undefined;
}

export function buildAttentionList<Reason>({
    apps = [],
    installations,
    canManageInstallations,
    pendingInvites = 0,
    unreadNotifications = 0,
}: AttentionInput<Reason>): readonly AttentionItem<Reason>[] {
    const items: AttentionItem<Reason>[] = [];

    for (const { app, repository } of apps) {
        if (repository.kind === "disconnected") {
            const { repoFullName, reason } = repository;
            items.push({ kind: "repository-disconnected", app, repoFullName, reason });
        } else if (repository.kind === "connected" && repository.archived) {
            items.push({ kind: "repository-archived", app, repoFullName: repository.repoFullName });
        }
    }
    for (const { accountLogin, suspended } of installations ?? []) {
        if (suspended) items.push({ kind: "installation-suspended", accountLogin });
    }
    if (installations?.length === 0 && canManageInstallations) items.push({ kind: "connect-github" });

    const [newest] = apps;
    if (newest !== undefined && apps.every(({ repository }) => repository.kind === "unlinked")) {
        items.push({ kind: "link-repository", app: newest.app });
    }
    if (pendingInvites > 0) items.push({ kind: "pending-invites", count: pendingInvites });
    if (unreadNotifications > 0) items.push({ kind: "unread-notifications", count: unreadNotifications });

    return rankAttention(items);
}
