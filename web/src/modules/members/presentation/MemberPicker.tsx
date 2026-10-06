"use client";

import type { OrgId, UserId } from "@/shared/domain/ids";
import { useDebouncedValue } from "@/shared/presentation/hooks";
import { Avatar, Field, Input, Person } from "@/shared/presentation/ui";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useId, useState, type KeyboardEvent, type ReactNode } from "react";
import type { Member } from "../domain/member";
import { MAX_MEMBER_SEARCH_LENGTH, memberPickerQuery } from "../domain/member-list-query";
import { PICKER_NO_MATCHES_COPY, PICKER_SEARCHING_COPY, SEARCH_PLACEHOLDER_COPY } from "./member-copy";
import { useMemberUseCases } from "./member-use-cases";
import styles from "./MemberPicker.module.css";
import { memberQueries } from "./queries";
import { SEARCH_DEBOUNCE_MS } from "./search-params";

export interface MemberPickerProps {
    readonly orgId: OrgId;
    readonly label: ReactNode;
    readonly onSelect: (member: Member) => void;
    /** People to leave out of the suggestions, such as those already on a team. */
    readonly excludeUserIds?: readonly UserId[];
    readonly hint?: ReactNode;
    readonly disabled?: boolean;
}

/** A combobox over the organization's active members, searched by name or email prefix on the server. */
export function MemberPicker({
    orgId,
    label,
    onSelect,
    excludeUserIds = [],
    hint,
    disabled = false,
}: MemberPickerProps) {
    const { listMembers } = useMemberUseCases();
    const listId = useId();
    const [draft, setDraft] = useState("");
    const [open, setOpen] = useState(false);
    const [active, setActive] = useState(0);
    const q = useDebouncedValue(draft, SEARCH_DEBOUNCE_MS);
    const members = useQuery({
        ...memberQueries.list(listMembers, orgId, memberPickerQuery(q)),
        enabled: open,
        placeholderData: keepPreviousData,
    });

    const excluded = new Set<string>(excludeUserIds);
    const options = (members.data?.items ?? []).filter((member) => !excluded.has(member.userId));
    const highlighted = Math.min(active, options.length - 1);
    const optionId = (index: number) => `${listId}-option-${String(index)}`;

    const choose = (member: Member) => {
        onSelect(member);
        setDraft("");
        setOpen(false);
        setActive(0);
    };

    const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
        switch (event.key) {
            case "ArrowDown":
                event.preventDefault();
                if (!open) setOpen(true);
                else setActive(Math.min(highlighted + 1, options.length - 1));
                break;
            case "ArrowUp":
                event.preventDefault();
                setActive(Math.max(highlighted - 1, 0));
                break;
            case "Enter": {
                const member = open ? options[highlighted] : undefined;
                if (member === undefined) return;
                event.preventDefault();
                choose(member);
                break;
            }
            case "Escape":
                if (!open) return;
                event.preventDefault();
                setOpen(false);
                break;
            default:
                break;
        }
    };

    const status = () => {
        if (members.data === undefined || members.isPlaceholderData) return PICKER_SEARCHING_COPY;
        if (options.length === 0) return PICKER_NO_MATCHES_COPY;
        return undefined;
    };
    const message = open ? status() : undefined;

    return (
        <Field label={label} hint={hint}>
            <div className={styles.picker}>
                <Input
                    role="combobox"
                    aria-expanded={open}
                    aria-controls={listId}
                    aria-autocomplete="list"
                    aria-activedescendant={open && highlighted >= 0 ? optionId(highlighted) : undefined}
                    placeholder={SEARCH_PLACEHOLDER_COPY}
                    autoComplete="off"
                    spellCheck={false}
                    maxLength={MAX_MEMBER_SEARCH_LENGTH}
                    disabled={disabled}
                    value={draft}
                    onChange={(event) => {
                        setDraft(event.currentTarget.value);
                        setActive(0);
                        setOpen(true);
                    }}
                    onFocus={() => {
                        setOpen(true);
                    }}
                    onBlur={() => {
                        setOpen(false);
                    }}
                    onKeyDown={onKeyDown}
                />
                <div className={styles.popover} hidden={!open}>
                    <div id={listId} role="listbox" aria-label="Members" className={styles.options}>
                        {open &&
                            options.map((member, index) => (
                                <div
                                    key={member.userId}
                                    id={optionId(index)}
                                    role="option"
                                    tabIndex={-1}
                                    aria-selected={index === highlighted}
                                    className={styles.option}
                                    onMouseDown={(event) => {
                                        event.preventDefault();
                                        choose(member);
                                    }}
                                    onMouseEnter={() => {
                                        setActive(index);
                                    }}
                                >
                                    <Person
                                        avatar={<Avatar name={member.displayName} size="sm" />}
                                        name={member.displayName}
                                        meta={member.email}
                                    />
                                </div>
                            ))}
                    </div>
                    <div role="status" className={styles.status} hidden={message === undefined}>
                        {message}
                    </div>
                </div>
            </div>
        </Field>
    );
}
