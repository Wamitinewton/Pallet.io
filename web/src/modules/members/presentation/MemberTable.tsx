"use client";

import { RoleTag } from "@/shared/presentation/roles";
import {
    Avatar,
    Person,
    RelativeTime,
    Skeleton,
    Table,
    TableWrap,
    Td,
    Th,
    VisuallyHidden,
} from "@/shared/presentation/ui";
import type { AriaAttributes } from "react";
import type { Member } from "../domain/member";
import { memberActions, type MemberAction, type Viewer } from "../domain/member-actions";
import type { MemberSort, MemberSortField } from "../domain/member-list-query";
import { SORT_COLUMN_LABEL } from "./member-copy";
import { MemberActionsMenu } from "./MemberActionsMenu";
import styles from "./MembersView.module.css";

export interface MemberTableProps {
    readonly members: readonly Member[];
    /** Undefined until the caller's own membership is known; no row offers an action until then. */
    readonly viewer: Viewer | undefined;
    readonly orgName: string;
    readonly sort: MemberSort;
    /** True while an earlier page stays on screen as the next one loads. */
    readonly busy?: boolean;
    readonly onAction: (action: MemberAction, member: Member) => void;
}

export function MemberTable({ members, viewer, orgName, sort, busy = false, onAction }: MemberTableProps) {
    return (
        <TableWrap>
            <Table aria-label="Members" aria-busy={busy} className={busy ? styles.stale : undefined}>
                <MemberTableHead sort={sort} />
                <tbody>
                    {members.map((member) => (
                        <tr key={member.userId}>
                            <Td>
                                <Person
                                    avatar={<Avatar name={member.displayName} />}
                                    name={
                                        <>
                                            {member.displayName}
                                            {member.userId === viewer?.userId && (
                                                <span className={styles.you}>you</span>
                                            )}
                                        </>
                                    }
                                    meta={member.email}
                                />
                            </Td>
                            <Td>
                                <RoleTag role={member.role} />
                            </Td>
                            <Td hideOnMobile className={styles.joined}>
                                <RelativeTime dateTime={member.joinedAt} />
                            </Td>
                            <Td actions>
                                <MemberActionsMenu
                                    member={member}
                                    actions={viewer === undefined ? [] : memberActions(member, viewer)}
                                    orgName={orgName}
                                    onSelect={onAction}
                                />
                            </Td>
                        </tr>
                    ))}
                </tbody>
            </Table>
        </TableWrap>
    );
}

function ariaSort(field: MemberSortField, sort: MemberSort): AriaAttributes["aria-sort"] {
    if (sort.field !== field) return undefined;
    return sort.direction === "asc" ? "ascending" : "descending";
}

function MemberTableHead({ sort }: Readonly<{ sort: MemberSort }>) {
    return (
        <thead>
            <tr>
                <Th aria-sort={ariaSort("displayName", sort)}>{SORT_COLUMN_LABEL.displayName}</Th>
                <Th aria-sort={ariaSort("role", sort)}>{SORT_COLUMN_LABEL.role}</Th>
                <Th hideOnMobile aria-sort={ariaSort("joinedAt", sort)}>
                    {SORT_COLUMN_LABEL.joinedAt}
                </Th>
                <Th actions>
                    <VisuallyHidden>Actions</VisuallyHidden>
                </Th>
            </tr>
        </thead>
    );
}

export function MemberTableSkeleton({ rows, sort }: Readonly<{ rows: number; sort: MemberSort }>) {
    return (
        <TableWrap>
            <Table aria-label="Loading members" aria-busy="true">
                <MemberTableHead sort={sort} />
                <tbody>
                    {Array.from({ length: rows }, (_, index) => (
                        <tr key={index}>
                            <Td>
                                <div className={styles.skeletonPerson}>
                                    <Skeleton circle width={32} height={32} />
                                    <div className={styles.skeletonText}>
                                        <Skeleton width="45%" height={14} />
                                        <Skeleton width="70%" height={12} />
                                    </div>
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
