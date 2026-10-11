"use client";

import { forgetGitHubSession, isGitHubSessionGone } from "@/modules/github";
import type { InstallationId, OrgId } from "@/shared/domain/ids";
import { ErrorState } from "@/shared/presentation/errors";
import {
    Badge,
    Callout,
    Icon,
    Pagination,
    SearchField,
    Skeleton,
    Stack,
    VisuallyHidden,
} from "@/shared/presentation/ui";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useId } from "react";
import {
    linkabilityOf,
    REPOSITORY_SEARCH_MAX_LENGTH,
    repositoryQuery,
    type PickerRepository,
} from "../domain/picker-repository";
import { repoLinkQueries } from "./queries";
import {
    ARCHIVED_BADGE_COPY,
    BLOCKED_REASON_COPY,
    FILTER_PLACEHOLDER_COPY,
    FILTER_REPOSITORIES_COPY,
    LOADING_REPOSITORIES_COPY,
    NO_MATCHING_REPOSITORIES_COPY,
    NO_REPOSITORIES_COPY,
    PICKER_SCOPE_COPY,
    PRIVATE_COPY,
    PUBLIC_COPY,
    REPOSITORIES_LABEL_COPY,
    repositoriesErrorCopy,
    yourPermissionCopy,
} from "./repo-link-copy";
import { useRepoLinkUseCases } from "./repo-link-use-cases";
import styles from "./RepoLinks.module.css";

const LOADING_ROWS = 4;

export interface RepositoryPickerProps {
    readonly orgId: OrgId;
    readonly installationId: InstallationId;
    /** As the address bar holds them: pages count from one. */
    readonly q: string;
    readonly page: number;
    readonly onSearch: (q: string) => void;
    readonly onPageChange: (page: number) => void;
    readonly selected: PickerRepository | undefined;
    readonly onSelect: (repository: PickerRepository) => void;
    /** Why the last link attempt was refused, when it was about the chosen repository. */
    readonly notice?: string | undefined;
}

/**
 * What the caller's own GitHub token reaches through the installation, never the installation's full list.
 * A row the backend says can't be linked stays visible, held back with the reason.
 */
export function RepositoryPicker({
    orgId,
    installationId,
    q,
    page,
    onSearch,
    onPageChange,
    selected,
    onSelect,
    notice,
}: RepositoryPickerProps) {
    const { listLinkableRepositories } = useRepoLinkUseCases();
    const queryClient = useQueryClient();
    const query = repositoryQuery(q, page);
    const repositories = useQuery({
        ...repoLinkQueries.repositories(listLinkableRepositories, orgId, installationId, query),
        placeholderData: keepPreviousData,
    });
    const sessionGone = isGitHubSessionGone(repositories.error);

    useEffect(() => {
        if (sessionGone) forgetGitHubSession(queryClient);
    }, [sessionGone, queryClient]);

    const body = () => {
        if (repositories.data === undefined) {
            return repositories.isError ? (
                <ErrorState
                    error={repositories.error}
                    headingLevel={3}
                    retrying={repositories.isFetching}
                    onRetry={() => void repositories.refetch()}
                    copy={repositoriesErrorCopy}
                />
            ) : (
                <div className={styles.repoList} role="status" aria-label={LOADING_REPOSITORIES_COPY}>
                    {Array.from({ length: LOADING_ROWS }, (_, index) => (
                        <Skeleton key={index} height={58} />
                    ))}
                </div>
            );
        }
        const { items, totalPages } = repositories.data;
        if (items.length === 0) {
            return (
                <p className={styles.muted}>{query.q === "" ? NO_REPOSITORIES_COPY : NO_MATCHING_REPOSITORIES_COPY}</p>
            );
        }
        return (
            <>
                <div
                    className={styles.repoList}
                    data-stale={repositories.isPlaceholderData || undefined}
                    role="radiogroup"
                    aria-label={REPOSITORIES_LABEL_COPY}
                    aria-busy={repositories.isPlaceholderData}
                >
                    {items.map((repository) => (
                        <RepositoryRow
                            key={repository.repoId}
                            repository={repository}
                            checked={selected?.repoId === repository.repoId}
                            onSelect={onSelect}
                        />
                    ))}
                </div>
                {totalPages > 1 && (
                    <Pagination
                        aria-label={`${REPOSITORIES_LABEL_COPY} pages`}
                        page={repositories.data.page + 1}
                        totalPages={totalPages}
                        disabled={repositories.isPlaceholderData}
                        onPageChange={onPageChange}
                    />
                )}
            </>
        );
    };

    return (
        <Stack>
            {notice !== undefined && (
                <Callout tone="red" role="alert">
                    {notice}
                </Callout>
            )}
            <SearchField
                value={query.q}
                onSearch={onSearch}
                label={FILTER_REPOSITORIES_COPY}
                placeholder={FILTER_PLACEHOLDER_COPY}
                maxLength={REPOSITORY_SEARCH_MAX_LENGTH}
            />
            {body()}
            <p className={styles.explanation}>{PICKER_SCOPE_COPY}</p>
        </Stack>
    );
}

interface RepositoryRowProps {
    readonly repository: PickerRepository;
    readonly checked: boolean;
    readonly onSelect: (repository: PickerRepository) => void;
}

function RepositoryRow({ repository, checked, onSelect }: RepositoryRowProps) {
    const reasonId = useId();
    const linkability = linkabilityOf(repository);
    const { fullName, isPrivate, defaultBranch, permission, archived } = repository;

    return (
        <label className={styles.pick} data-blocked={!linkability.linkable || undefined}>
            <input
                type="radio"
                name="repository"
                value={String(repository.repoId)}
                checked={checked}
                disabled={!linkability.linkable}
                aria-describedby={linkability.linkable ? undefined : reasonId}
                onChange={() => {
                    onSelect(repository);
                }}
            />
            <span className={styles.pickText}>
                <span className={styles.pickName}>{fullName}</span>
                <span className={styles.pickSub}>
                    <span>
                        <Icon name={isPrivate ? "lock" : "globe"} />
                        {isPrivate ? PRIVATE_COPY : PUBLIC_COPY}
                    </span>
                    <span>
                        <Icon name="branch" />
                        <VisuallyHidden>Default branch: </VisuallyHidden>
                        {defaultBranch}
                    </span>
                    <span>{yourPermissionCopy(permission)}</span>
                </span>
            </span>
            {archived && (
                <Badge>
                    <Icon name="archive" />
                    {ARCHIVED_BADGE_COPY}
                </Badge>
            )}
            {!linkability.linkable &&
                (archived ? (
                    <VisuallyHidden id={reasonId}>{BLOCKED_REASON_COPY[linkability.reason]}</VisuallyHidden>
                ) : (
                    <span id={reasonId} className={styles.blockedReason}>
                        {BLOCKED_REASON_COPY[linkability.reason]}
                    </span>
                ))}
        </label>
    );
}
