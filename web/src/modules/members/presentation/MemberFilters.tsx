"use client";

import { isRole, ROLES } from "@/shared/domain/role";
import { ROLE_LABEL } from "@/shared/presentation/roles";
import { Icon, Input, InputGroup, Segmented, SegmentedItem, Select } from "@/shared/presentation/ui";
import { useEffect, useRef, useState } from "react";
import { MEMBER_STATUSES } from "../domain/member";
import {
    formatMemberSort,
    MAX_MEMBER_SEARCH_LENGTH,
    normalizeMemberSearch,
    parseMemberSort,
    type MemberFilterChange,
    type MemberListParams,
} from "../domain/member-list-query";
import { ALL_ROLES_COPY, SEARCH_LABEL_COPY, SEARCH_PLACEHOLDER_COPY, SORT_LABEL, STATUS_LABEL } from "./member-copy";
import styles from "./MembersView.module.css";
import { SEARCH_DEBOUNCE_MS } from "./search-params";

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

interface SearchFieldProps {
    readonly value: string;
    readonly onSearch: (q: string) => void;
}

/**
 * Sends the search once typing pauses. Follows the URL when it changes from elsewhere, such as the back
 * button or clearing the filters, and drops a pending search that the change made stale.
 */
function SearchField({ value, onSearch }: SearchFieldProps) {
    const [draft, setDraft] = useState(value);
    const [followed, setFollowed] = useState(value);
    const latestDraft = useRef(draft);
    const pending = useRef<ReturnType<typeof setTimeout>>(undefined);

    if (value !== followed) {
        setFollowed(value);
        if (value !== normalizeMemberSearch(draft)) setDraft(value);
    }

    useEffect(() => {
        latestDraft.current = draft;
    }, [draft]);

    useEffect(
        () => () => {
            clearTimeout(pending.current);
        },
        [],
    );

    return (
        <InputGroup addon={<Icon name="search" />} className={styles.search}>
            <Input
                type="search"
                aria-label={SEARCH_LABEL_COPY}
                placeholder={SEARCH_PLACEHOLDER_COPY}
                autoComplete="off"
                spellCheck={false}
                maxLength={MAX_MEMBER_SEARCH_LENGTH}
                value={draft}
                onChange={(event) => {
                    const next = event.currentTarget.value;
                    setDraft(next);
                    latestDraft.current = next;
                    clearTimeout(pending.current);
                    pending.current = setTimeout(() => {
                        if (latestDraft.current !== next) return;
                        const q = normalizeMemberSearch(next);
                        if (q !== value) onSearch(q);
                    }, SEARCH_DEBOUNCE_MS);
                }}
            />
        </InputGroup>
    );
}
