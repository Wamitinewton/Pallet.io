"use client";

import { githubPath, type InstallationLink } from "@/modules/github";
import type { InstallationId, OrgId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import { Button, EmptyState, Field, Icon, Select, Skeleton } from "@/shared/presentation/ui";
import type { UseQueryResult } from "@tanstack/react-query";
import Link from "next/link";
import {
    ACCOUNT_KIND,
    CONNECT_GITHUB_COPY,
    INSTALL_THERE_COPY,
    INSTALLATION_LABEL_COPY,
    INSTALLATIONS_UNAVAILABLE_COPY,
    LOADING_INSTALLATIONS_COPY,
    MISSING_ACCOUNT_COPY,
    NO_INSTALLATIONS_COPY,
    NO_INSTALLATIONS_TITLE_COPY,
    SUSPENDED_SUFFIX_COPY,
    TRY_AGAIN_COPY,
} from "./repo-link-copy";
import styles from "./RepoLinks.module.css";

export interface InstallationPickerProps {
    readonly orgId: OrgId;
    readonly installations: UseQueryResult<Page<InstallationLink>>;
    /** Undefined while there is nothing to choose. */
    readonly value: InstallationId | undefined;
    readonly onChange: (installationId: InstallationId) => void;
}

function optionLabel({ accountLogin, accountType, status }: InstallationLink): string {
    const label = `${accountLogin} · ${ACCOUNT_KIND[accountType]}`;
    return status === "SUSPENDED" ? `${label} · ${SUSPENDED_SUFFIX_COPY}` : label;
}

/** The organization's installations; a suspended one is listed but can't be chosen, since linking would fail. */
export function InstallationPicker({ orgId, installations, value, onChange }: InstallationPickerProps) {
    if (installations.data === undefined) {
        return installations.isError ? (
            <div className={styles.inlineError} role="alert">
                <span>{INSTALLATIONS_UNAVAILABLE_COPY}</span>
                <Button
                    variant="secondary"
                    size="sm"
                    loading={installations.isFetching}
                    onClick={() => void installations.refetch()}
                >
                    {TRY_AGAIN_COPY}
                </Button>
            </div>
        ) : (
            <div role="status" aria-label={LOADING_INSTALLATIONS_COPY}>
                <Skeleton height={38} />
            </div>
        );
    }

    if (value === undefined) {
        return (
            <EmptyState
                icon="github"
                headingLevel={3}
                title={NO_INSTALLATIONS_TITLE_COPY}
                description={NO_INSTALLATIONS_COPY}
                actions={
                    <Button asChild variant="secondary" size="sm">
                        <Link href={githubPath(orgId)}>
                            <Icon name="github" />
                            {CONNECT_GITHUB_COPY}
                        </Link>
                    </Button>
                }
            />
        );
    }

    return (
        <Field
            label={INSTALLATION_LABEL_COPY}
            hint={
                <>
                    {MISSING_ACCOUNT_COPY} <Link href={githubPath(orgId)}>{INSTALL_THERE_COPY}</Link> first.
                </>
            }
        >
            <Select
                value={String(value)}
                onChange={(event) => {
                    const chosen = installations.data.items.find(
                        ({ installationId }) => String(installationId) === event.currentTarget.value,
                    );
                    if (chosen !== undefined) onChange(chosen.installationId);
                }}
            >
                {installations.data.items.map((installation) => (
                    <option
                        key={installation.installationId}
                        value={String(installation.installationId)}
                        disabled={installation.status !== "ACTIVE"}
                    >
                        {optionLabel(installation)}
                    </option>
                ))}
            </Select>
        </Field>
    );
}
