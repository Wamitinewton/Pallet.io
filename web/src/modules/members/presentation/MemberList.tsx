"use client";

import type { OrgId } from "@/shared/domain/ids";
import { ErrorState } from "@/shared/presentation/errors";
import { Button, EmptyState, Pagination, Panel, PanelFoot } from "@/shared/presentation/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import type { Member } from "../domain/member";
import type { MemberAction, Viewer } from "../domain/member-actions";
import { isFiltered, memberListQuery, type MemberListQuery } from "../domain/member-list-query";
import { ChangeRoleDialog } from "./ChangeRoleDialog";
import {
    CLEAR_FILTERS_COPY,
    FIRST_PAGE_COPY,
    memberCountCopy,
    NO_MATCHES_TITLE_COPY,
    NO_REMOVED_COPY,
    NO_REMOVED_TITLE_COPY,
    noMatchesCopy,
    oneOwnerCopy,
    PAST_LAST_PAGE_TITLE_COPY,
} from "./member-copy";
import { useMemberUseCases } from "./member-use-cases";
import { MemberFilters } from "./MemberFilters";
import styles from "./MembersView.module.css";
import { MemberTable, MemberTableSkeleton } from "./MemberTable";
import { useOrgAccess } from "./OrgAccessProvider";
import { memberQueries } from "./queries";
import { RemoveMemberDialog } from "./RemoveMemberDialog";
import { TransferOwnershipDialog } from "./TransferOwnershipDialog";
import { useMemberListParams } from "./use-member-list-params";

const LOADING_ROWS = 4;

export interface MemberListProps {
    readonly orgId: OrgId;
    readonly orgName: string;
}

interface OpenAction {
    readonly action: MemberAction;
    readonly member: Member;
}

/** The members tab: filters, the table, its pages, and the dialogs its rows open. */
export function MemberList({ orgId, orgName }: MemberListProps) {
    const access = useOrgAccess();
    const { getMyMembership, listMembers } = useMemberUseCases();
    const me = useQuery(memberQueries.mine(getMyMembership, orgId)).data;
    const { params, changeFilters, goToPage } = useMemberListParams();
    const query = memberListQuery(
        params,
        access.role === undefined ? undefined : { role: access.role, orgKind: access.orgKind },
    );
    const members = useQuery({ ...memberQueries.list(listMembers, orgId, query), placeholderData: keepPreviousData });
    const [open, setOpen] = useState<OpenAction>();

    const viewer: Viewer | undefined = me && { userId: me.userId, role: me.role };
    const opened = (action: MemberAction) => (open?.action === action ? open.member : undefined);
    const close = () => {
        setOpen(undefined);
    };

    const body = () => {
        if (members.isError && members.data === undefined) {
            return (
                <ErrorState
                    error={members.error}
                    headingLevel={2}
                    retrying={members.isFetching}
                    onRetry={() => void members.refetch()}
                />
            );
        }
        if (members.data === undefined) return <MemberTableSkeleton rows={LOADING_ROWS} sort={query.sort} />;
        if (members.data.items.length === 0 && members.data.totalItems > 0) {
            return (
                <EmptyState
                    icon="users"
                    title={PAST_LAST_PAGE_TITLE_COPY}
                    actions={
                        <Button
                            variant="secondary"
                            size="sm"
                            onClick={() => {
                                goToPage(1);
                            }}
                        >
                            {FIRST_PAGE_COPY}
                        </Button>
                    }
                />
            );
        }
        if (members.data.items.length === 0) {
            return (
                <NoMembers
                    query={query}
                    onClear={() => {
                        changeFilters({ q: "", role: null, status: "ACTIVE" });
                    }}
                />
            );
        }
        return (
            <MemberTable
                members={members.data.items}
                viewer={viewer}
                orgName={orgName}
                sort={query.sort}
                busy={members.isPlaceholderData}
                onAction={(action, member) => {
                    setOpen({ action, member });
                }}
            />
        );
    };

    return (
        <>
            <MemberFilters
                params={params}
                canViewRemoved={access.can("members.viewRemoved")}
                onChange={changeFilters}
            />

            <Panel className={styles.panel}>
                {body()}
                {members.data !== undefined && members.data.totalItems > 0 && (
                    <PanelFoot>
                        <span className={styles.count}>
                            {memberCountCopy(members.data.totalItems, query.status === "REMOVED")}
                        </span>
                        {access.role === "OWNER" && query.status === "ACTIVE" && <span>{oneOwnerCopy(orgName)}</span>}
                    </PanelFoot>
                )}
            </Panel>

            {members.data !== undefined && (
                <Pagination
                    aria-label="Members pages"
                    className={styles.pagination}
                    page={members.data.page + 1}
                    totalPages={members.data.totalPages}
                    disabled={members.isPlaceholderData}
                    onPageChange={goToPage}
                />
            )}

            <ChangeRoleDialog orgId={orgId} orgName={orgName} member={opened("changeRole")} onClose={close} />
            <TransferOwnershipDialog
                orgId={orgId}
                orgName={orgName}
                member={opened("transferOwnership")}
                onClose={close}
            />
            <RemoveMemberDialog
                orgId={orgId}
                orgName={orgName}
                member={opened("remove") ?? opened("leave")}
                leaving={open?.action === "leave"}
                onClose={close}
            />
        </>
    );
}

interface NoMembersProps {
    readonly query: MemberListQuery;
    readonly onClear: () => void;
}

function NoMembers({ query, onClear }: NoMembersProps) {
    const onlyRemoved = query.status === "REMOVED" && query.q === "" && query.role === null;
    return (
        <EmptyState
            icon={onlyRemoved ? "users" : "search"}
            title={onlyRemoved ? NO_REMOVED_TITLE_COPY : NO_MATCHES_TITLE_COPY}
            description={onlyRemoved ? NO_REMOVED_COPY : noMatchesCopy(query.q)}
            actions={
                isFiltered(query) && (
                    <Button variant="secondary" size="sm" onClick={onClear}>
                        {CLEAR_FILTERS_COPY}
                    </Button>
                )
            }
        />
    );
}
