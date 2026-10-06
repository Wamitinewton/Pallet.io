"use client";

import { useMemberDirectory, useOrgAccess, type MemberDirectory } from "@/modules/members";
import type { OrgId } from "@/shared/domain/ids";
import { ErrorState } from "@/shared/presentation/errors";
import {
    Badge,
    Button,
    EmptyState,
    Icon,
    Menu,
    MenuContent,
    MenuItem,
    MenuSeparator,
    MenuTrigger,
    Panel,
    PanelFoot,
    PanelHead,
    Person,
    RelativeTime,
    Skeleton,
    Table,
    TableWrap,
    Td,
    Th,
    VisuallyHidden,
} from "@/shared/presentation/ui";
import { useQuery } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { installationSettingsUrl, type InstallationLink } from "../domain/installation";
import {
    ACCOUNT_TYPE_LABEL,
    actionsLabel,
    COLUMN_LABEL,
    FIRST_PAGE_ONLY_COPY,
    FORMER_MEMBER_COPY,
    INSTALLATIONS_DESCRIPTION_COPY,
    INSTALLATIONS_TITLE_COPY,
    linkedByCopy,
    LOADING_INSTALLATIONS_COPY,
    MANAGE_ON_GITHUB_COPY,
    NOT_CONNECTED_ADMIN_COPY,
    NOT_CONNECTED_COPY,
    NOT_CONNECTED_TITLE_COPY,
    removeFromCopy,
    STATUS_LABEL,
    SUSPENDED_FOOT_COPY,
} from "./github-copy";
import { useGitHubUseCases } from "./github-use-cases";
import styles from "./GitHub.module.css";
import { GitHubAvatar } from "./GitHubAvatar";
import { githubQueries } from "./queries";
import { UnlinkInstallationDialog } from "./UnlinkInstallationDialog";

const LOADING_ROWS = 2;

export interface InstallationListProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    /** Shown to admins when nothing is connected yet. */
    readonly connectAction?: ReactNode;
}

export function InstallationList({ orgId, orgName, connectAction }: InstallationListProps) {
    const canManage = useOrgAccess().can("github.installations.manage");
    const { listOrgInstallations } = useGitHubUseCases();
    const installations = useQuery(githubQueries.installations(listOrgInstallations, orgId));
    const members = useMemberDirectory(orgId);
    const [unlinking, setUnlinking] = useState<InstallationLink>();

    if (installations.data === undefined) {
        return (
            <Panel className={styles.panel} aria-label={INSTALLATIONS_TITLE_COPY}>
                <PanelHead title={INSTALLATIONS_TITLE_COPY} description={INSTALLATIONS_DESCRIPTION_COPY} />
                {installations.isError ? (
                    <ErrorState
                        error={installations.error}
                        headingLevel={3}
                        retrying={installations.isFetching}
                        onRetry={() => void installations.refetch()}
                    />
                ) : (
                    <InstallationTableSkeleton rows={LOADING_ROWS} />
                )}
            </Panel>
        );
    }

    const { items, totalItems } = installations.data;
    if (items.length === 0) {
        return (
            <Panel className={styles.panel}>
                <EmptyState
                    icon="github"
                    title={NOT_CONNECTED_TITLE_COPY}
                    description={canManage ? NOT_CONNECTED_ADMIN_COPY : NOT_CONNECTED_COPY}
                    actions={canManage ? connectAction : undefined}
                />
            </Panel>
        );
    }

    const anySuspended = items.some((installation) => installation.status === "SUSPENDED");
    const truncated = totalItems > items.length;

    return (
        <Panel className={styles.panel} aria-label={INSTALLATIONS_TITLE_COPY}>
            <PanelHead title={INSTALLATIONS_TITLE_COPY} description={INSTALLATIONS_DESCRIPTION_COPY} />
            <TableWrap>
                <Table aria-label={INSTALLATIONS_TITLE_COPY}>
                    <InstallationTableHead />
                    <tbody>
                        {items.map((installation) => (
                            <InstallationRow
                                key={installation.installationId}
                                installation={installation}
                                orgName={orgName}
                                members={members}
                                canManage={canManage}
                                onUnlink={setUnlinking}
                            />
                        ))}
                    </tbody>
                </Table>
            </TableWrap>
            {(anySuspended || truncated) && (
                <PanelFoot>
                    {anySuspended && <span>{SUSPENDED_FOOT_COPY}</span>}
                    {truncated && <span>{FIRST_PAGE_ONLY_COPY}</span>}
                </PanelFoot>
            )}
            <UnlinkInstallationDialog
                orgId={orgId}
                orgName={orgName}
                installation={unlinking}
                onClose={() => {
                    setUnlinking(undefined);
                }}
            />
        </Panel>
    );
}

interface InstallationRowProps {
    readonly installation: InstallationLink;
    readonly orgName: string;
    readonly members: MemberDirectory | undefined;
    readonly canManage: boolean;
    readonly onUnlink: (installation: InstallationLink) => void;
}

function InstallationRow({ installation, orgName, members, canManage, onUnlink }: InstallationRowProps) {
    const { accountLogin, accountType, status, linkedAt, linkedByUserId } = installation;
    const linkedBy =
        members === undefined ? undefined : (members.get(linkedByUserId)?.displayName ?? FORMER_MEMBER_COPY);

    return (
        <tr>
            <Td>
                <Person
                    avatar={<GitHubAvatar login={accountLogin} />}
                    name={<span className={styles.login}>{accountLogin}</span>}
                    meta={ACCOUNT_TYPE_LABEL[accountType]}
                />
            </Td>
            <Td>
                <Badge tone={status === "ACTIVE" ? "green" : "amber"} dot={status === "ACTIVE"}>
                    {STATUS_LABEL[status]}
                </Badge>
            </Td>
            <Td hideOnMobile>
                <RelativeTime dateTime={linkedAt} />
                {linkedBy !== undefined && <span className={styles.linkedBy}>{linkedByCopy(linkedBy)}</span>}
            </Td>
            <Td actions>
                <Menu>
                    <MenuTrigger>
                        <Button variant="ghost" size="sm" iconOnly aria-label={actionsLabel(accountLogin)}>
                            <Icon name="more" />
                        </Button>
                    </MenuTrigger>
                    <MenuContent>
                        <MenuItem asChild>
                            <a href={installationSettingsUrl(installation)}>
                                <Icon name="external" />
                                {MANAGE_ON_GITHUB_COPY}
                            </a>
                        </MenuItem>
                        {canManage && (
                            <>
                                <MenuSeparator />
                                <MenuItem
                                    tone="danger"
                                    onSelect={() => {
                                        onUnlink(installation);
                                    }}
                                >
                                    <Icon name="trash" />
                                    {removeFromCopy(orgName)}
                                </MenuItem>
                            </>
                        )}
                    </MenuContent>
                </Menu>
            </Td>
        </tr>
    );
}

function InstallationTableHead() {
    return (
        <thead>
            <tr>
                <Th>{COLUMN_LABEL.account}</Th>
                <Th>{COLUMN_LABEL.status}</Th>
                <Th hideOnMobile>{COLUMN_LABEL.linked}</Th>
                <Th actions>
                    <VisuallyHidden>{COLUMN_LABEL.actions}</VisuallyHidden>
                </Th>
            </tr>
        </thead>
    );
}

function InstallationTableSkeleton({ rows }: Readonly<{ rows: number }>) {
    return (
        <TableWrap>
            <Table aria-label={LOADING_INSTALLATIONS_COPY} aria-busy="true">
                <InstallationTableHead />
                <tbody>
                    {Array.from({ length: rows }, (_, index) => (
                        <tr key={index}>
                            <Td>
                                <div className={styles.skeletonAccount}>
                                    <Skeleton width={32} height={32} />
                                    <Skeleton width={120} height={14} />
                                </div>
                            </Td>
                            <Td>
                                <Skeleton width={64} height={22} />
                            </Td>
                            <Td hideOnMobile>
                                <Skeleton width={80} height={12} />
                            </Td>
                            <Td actions />
                        </tr>
                    ))}
                </tbody>
            </Table>
        </TableWrap>
    );
}
