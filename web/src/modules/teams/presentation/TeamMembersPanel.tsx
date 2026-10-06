"use client";

import { useOrgAccess } from "@/modules/members";
import type { OrgId } from "@/shared/domain/ids";
import { ErrorState } from "@/shared/presentation/errors";
import { RoleTag } from "@/shared/presentation/roles";
import {
    Avatar,
    Button,
    EmptyState,
    Icon,
    List,
    ListItem,
    Pagination,
    Panel,
    PanelFoot,
    PanelHead,
    Person,
    Skeleton,
} from "@/shared/presentation/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useId, useState, type ReactNode } from "react";
import type { Team, TeamMember } from "../domain/team";
import { teamMemberListQuery } from "../domain/team-list-query";
import { teamQueries } from "./queries";
import { RemoveTeamMemberDialog } from "./RemoveTeamMemberDialog";
import {
    ADD_PEOPLE_COPY,
    FIRST_PAGE_COPY,
    LOADING_PEOPLE_COPY,
    NO_PEOPLE_ADMIN_COPY,
    NO_PEOPLE_COPY,
    NO_PEOPLE_TITLE_COPY,
    PAST_LAST_PAGE_TITLE_COPY,
    PEOPLE_TITLE_COPY,
    REMOVE_COPY,
    removeLabel,
    ROLES_COME_FROM_ORG_COPY,
    YOU_COPY,
} from "./team-copy";
import { useTeamUseCases } from "./team-use-cases";
import styles from "./Teams.module.css";
import { useRemoveTeamMember } from "./use-team-mutations";
import { useTeamMemberPage } from "./use-team-params";

const LOADING_ROWS = 3;

export interface TeamMembersPanelProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    readonly team: Team;
    /** Opens the add dialog; absent for anyone who can't add people. */
    readonly onAddPeople?: (() => void) | undefined;
}

export function TeamMembersPanel({ orgId, orgName, team, onAddPeople }: TeamMembersPanelProps) {
    const access = useOrgAccess();
    const { listTeamMembers } = useTeamUseCases();
    const [page, goToPage] = useTeamMemberPage();
    const members = useQuery({
        ...teamQueries.members(listTeamMembers, orgId, team.id, teamMemberListQuery(page)),
        placeholderData: keepPreviousData,
    });
    const remove = useRemoveTeamMember(orgId, team);
    const [removing, setRemoving] = useState<TeamMember>();
    const canManage = access.can("teams.manage");
    const headingId = useId();

    const body = (): ReactNode => {
        if (members.isError && members.data === undefined) {
            return (
                <ErrorState
                    error={members.error}
                    headingLevel={3}
                    retrying={members.isFetching}
                    onRetry={() => void members.refetch()}
                />
            );
        }
        if (members.data === undefined) {
            return (
                <List aria-label={LOADING_PEOPLE_COPY} aria-busy="true">
                    {Array.from({ length: LOADING_ROWS }, (_, index) => (
                        <ListItem key={index}>
                            <Skeleton circle width={32} height={32} />
                            <Skeleton width="40%" height={14} />
                        </ListItem>
                    ))}
                </List>
            );
        }
        if (members.data.items.length === 0 && members.data.totalItems > 0) {
            return (
                <EmptyState
                    icon="users"
                    headingLevel={3}
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
                <EmptyState
                    icon="users"
                    headingLevel={3}
                    title={NO_PEOPLE_TITLE_COPY}
                    description={onAddPeople ? NO_PEOPLE_ADMIN_COPY : NO_PEOPLE_COPY}
                    actions={
                        onAddPeople && (
                            <Button variant="secondary" size="sm" onClick={onAddPeople}>
                                <Icon name="plus" />
                                {ADD_PEOPLE_COPY}
                            </Button>
                        )
                    }
                />
            );
        }
        return (
            <List aria-label={PEOPLE_TITLE_COPY} aria-busy={members.isPlaceholderData}>
                {members.data.items.map((member) => (
                    <ListItem key={member.userId}>
                        <Person
                            className={styles.person}
                            avatar={<Avatar name={member.displayName} />}
                            name={
                                <>
                                    {member.displayName}
                                    {member.userId === access.userId && <span className={styles.you}>{YOU_COPY}</span>}
                                </>
                            }
                            meta={member.email}
                        />
                        <RoleTag role={member.role} className={styles.role} />
                        {canManage && (
                            <Button
                                variant="ghost"
                                size="sm"
                                aria-label={removeLabel(member.displayName, team.name)}
                                onClick={() => {
                                    setRemoving(member);
                                }}
                            >
                                {REMOVE_COPY}
                            </Button>
                        )}
                    </ListItem>
                ))}
            </List>
        );
    };

    return (
        <div>
            <Panel className={styles.panel} aria-labelledby={headingId}>
                <PanelHead title={<span id={headingId}>{PEOPLE_TITLE_COPY}</span>} />
                {body()}
                <PanelFoot>
                    <span>{ROLES_COME_FROM_ORG_COPY}</span>
                </PanelFoot>
            </Panel>
            {members.data !== undefined && (
                <Pagination
                    aria-label="People pages"
                    className={styles.pagination}
                    page={members.data.page + 1}
                    totalPages={members.data.totalPages}
                    disabled={members.isPlaceholderData}
                    onPageChange={goToPage}
                />
            )}
            <RemoveTeamMemberDialog
                teamName={team.name}
                orgName={orgName}
                member={removing}
                onConfirm={(member) => {
                    remove.mutate(member);
                }}
                onClose={() => {
                    setRemoving(undefined);
                }}
            />
        </div>
    );
}
