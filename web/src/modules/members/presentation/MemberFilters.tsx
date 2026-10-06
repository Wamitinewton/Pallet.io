"use client";

import { isRole, ROLES } from "@/shared/domain/role";
import { ROLE_LABEL } from "@/shared/presentation/roles";
import { SearchField, Segmented, SegmentedItem, Select } from "@/shared/presentation/ui";
import { MEMBER_STATUSES } from "../domain/member";
import {
    formatMemberSort,
    MAX_MEMBER_SEARCH_LENGTH,
    parseMemberSort,
    type MemberFilterChange,
    type MemberListParams,
} from "../domain/member-list-query";
import { ALL_ROLES_COPY, SEARCH_LABEL_COPY, SEARCH_PLACEHOLDER_COPY, SORT_LABEL, STATUS_LABEL } from "./member-copy";
import styles from "./MembersView.module.css";

const SORT_OPTIONS = Object.entries(SORT_LABEL);

export interface MemberFiltersProps {
    readonly params: MemberListParams;
    readonly canViewRemoved: boolean;
    readonly onChange: (change: MemberFilterChange) => void;
}

export function MemberFilters({ params, canViewRemoved, onChange }: MemberFiltersProps) {
    return (
        <div className={styles.filters} role="search" aria-label="Filter members">
            <SearchField
                className={styles.search}
                label={SEARCH_LABEL_COPY}
                placeholder={SEARCH_PLACEHOLDER_COPY}
                maxLength={MAX_MEMBER_SEARCH_LENGTH}
                value={params.q}
                onSearch={(q) => {
                    onChange({ q });
                }}
            />
            <div className={styles.filterControls}>
                <Select
                    aria-label="Role"
                    className={styles.filterSelect}
                    value={params.role ?? ""}
                    onChange={(event) => {
                        const value = event.currentTarget.value;
                        onChange({ role: isRole(value) ? value : null });
                    }}
                >
                    <option value="">{ALL_ROLES_COPY}</option>
                    {ROLES.toReversed().map((role) => (
                        <option key={role} value={role}>
                            {ROLE_LABEL[role]}
                        </option>
                    ))}
                </Select>
                <Select
                    aria-label="Sort"
                    className={styles.filterSelect}
                    value={formatMemberSort(params.sort)}
                    onChange={(event) => {
                        const sort = parseMemberSort(event.currentTarget.value);
                        if (sort !== undefined) onChange({ sort });
                    }}
                >
                    {SORT_OPTIONS.map(([value, label]) => (
                        <option key={value} value={value}>
                            {label}
                        </option>
                    ))}
                </Select>
                {canViewRemoved && (
                    <Segmented
                        aria-label="Status"
                        value={params.status}
                        onValueChange={(status) => {
                            onChange({ status });
                        }}
                    >
                        {MEMBER_STATUSES.map((status) => (
                            <SegmentedItem key={status} value={status}>
                                {STATUS_LABEL[status]}
                            </SegmentedItem>
                        ))}
                    </Segmented>
                )}
            </div>
        </div>
    );
}
