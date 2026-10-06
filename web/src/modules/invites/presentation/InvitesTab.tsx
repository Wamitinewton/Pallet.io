"use client";

import { useMemberDirectory, useOrgAccess } from "@/modules/members";
import { ErrorState } from "@/shared/presentation/errors";
import {
    Button,
    EmptyState,
    Icon,
    Pagination,
    Panel,
    PanelFoot,
    Segmented,
    SegmentedItem,
} from "@/shared/presentation/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import type { Invite } from "../domain/invite";
import {
    INVITE_STATUS_FILTERS,
    inviteListQuery,
    type InviteListQuery,
    type InviteStatusFilter,
} from "../domain/invite-list-query";
import {
    FILTER_LABEL,
    FIRST_PAGE_COPY,
    INVITE_PEOPLE_COPY,
    inviteCountCopy,
    INVITES_FOOTNOTE_COPY,
    noInvitesCopy,
    noInvitesTitle,
    PAST_LAST_PAGE_TITLE_COPY,
    STATUS_FILTER_LABEL_COPY,
} from "./invite-copy";
import { useInviteUseCases } from "./invite-use-cases";
import styles from "./Invites.module.css";
import { useInvites } from "./InvitesProvider";
import { InviteTable, InviteTableSkeleton, type InviteViewer } from "./InviteTable";
import { inviteQueries } from "./queries";
import { RevokeInviteDialog } from "./RevokeInviteDialog";
import { useInviteListParams } from "./use-invite-list-params";

const LOADING_ROWS = 3;

/**
 * The invites tab of the members page. Renders nothing, and asks for nothing, for anyone who can't manage
 * invites, which includes everyone in a personal organization.
 */
export function InvitesTab() {
    const access = useOrgAccess();
    const { params, showStatus, goToPage } = useInviteListParams();
    const query = inviteListQuery(
        params,
        access.role === undefined ? undefined : { role: access.role, orgKind: access.orgKind },
    );
    if (query === undefined || access.role === undefined) return null;

    return (
        <InviteList
            query={query}
            statusFilter={params.inviteStatus}
            viewer={{ userId: access.userId, role: access.role }}
            onStatusChange={showStatus}
            onPageChange={goToPage}
        />
    );
}

interface InviteListProps {
    readonly query: InviteListQuery;
    readonly statusFilter: InviteStatusFilter;
    readonly viewer: InviteViewer;
    readonly onStatusChange: (status: InviteStatusFilter) => void;
    readonly onPageChange: (page: number) => void;
}

function InviteList({ query, statusFilter, viewer, onStatusChange, onPageChange }: InviteListProps) {
    const { orgId, openInviteDialog } = useInvites();
    const { listInvites } = useInviteUseCases();
    const invites = useQuery({ ...inviteQueries.list(listInvites, orgId, query), placeholderData: keepPreviousData });
    const directory = useMemberDirectory(orgId);
    const [revoking, setRevoking] = useState<Invite>();

    const body = () => {
        if (invites.isError && invites.data === undefined) {
            return (
                <ErrorState
                    error={invites.error}
                    headingLevel={2}
                    retrying={invites.isFetching}
                    onRetry={() => void invites.refetch()}
                />
            );
        }
        if (invites.data === undefined) return <InviteTableSkeleton rows={LOADING_ROWS} />;
        if (invites.data.items.length === 0 && invites.data.totalItems > 0) {
            return (
                <EmptyState
                    icon="mail"
                    title={PAST_LAST_PAGE_TITLE_COPY}
                    actions={
                        <Button
                            variant="secondary"
                            size="sm"
                            onClick={() => {
                                onPageChange(1);
                            }}
                        >
                            {FIRST_PAGE_COPY}
                        </Button>
                    }
                />
            );
        }
        if (invites.data.items.length === 0) {
            return (
                <EmptyState
                    icon="mail"
                    title={noInvitesTitle(statusFilter)}
                    description={noInvitesCopy(statusFilter)}
                    actions={
                        (statusFilter === "PENDING" || statusFilter === "ALL") && (
                            <Button
                                variant="secondary"
                                size="sm"
                                onClick={() => {
                                    openInviteDialog();
                                }}
                            >
                                <Icon name="mail" />
                                {INVITE_PEOPLE_COPY}
                            </Button>
                        )
                    }
                />
            );
        }
        return (
            <InviteTable
                invites={invites.data.items}
                viewer={viewer}
                directory={directory}
                busy={invites.isPlaceholderData}
                onRevoke={setRevoking}
                onInviteAgain={(invite) => {
                    openInviteDialog({ email: invite.email, role: invite.role });
                }}
            />
        );
    };

    return (
        <>
            <div className={styles.toolbar}>
                <Segmented aria-label={STATUS_FILTER_LABEL_COPY} value={statusFilter} onValueChange={onStatusChange}>
                    {INVITE_STATUS_FILTERS.map((status) => (
                        <SegmentedItem key={status} value={status}>
                            {FILTER_LABEL[status]}
                        </SegmentedItem>
                    ))}
                </Segmented>
            </div>

            <Panel className={styles.panel}>
                {body()}
                {invites.data !== undefined && invites.data.totalItems > 0 && (
                    <PanelFoot>
                        <span className={styles.count}>{inviteCountCopy(invites.data.totalItems, statusFilter)}</span>
                        <span>{INVITES_FOOTNOTE_COPY}</span>
                    </PanelFoot>
                )}
            </Panel>

            {invites.data !== undefined && (
                <Pagination
                    aria-label="Invites pages"
                    className={styles.pagination}
                    page={invites.data.page + 1}
                    totalPages={invites.data.totalPages}
                    disabled={invites.isPlaceholderData}
                    onPageChange={onPageChange}
                />
            )}

            <RevokeInviteDialog
                orgId={orgId}
                invite={revoking}
                onClose={() => {
                    setRevoking(undefined);
                }}
            />
        </>
    );
}
