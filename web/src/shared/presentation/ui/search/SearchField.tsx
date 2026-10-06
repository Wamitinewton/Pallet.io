"use client";

import { useEffect, useRef, useState } from "react";
import { Icon } from "../icons/Icon";
import { Input, InputGroup } from "../input/Input";

/** How long typing has to pause before a search reaches the URL and the backend. */
export const SEARCH_DEBOUNCE_MS = 300;

export interface SearchFieldProps {
    readonly value: string;
    readonly onSearch: (q: string) => void;
    readonly label: string;
    readonly placeholder: string;
    readonly maxLength: number;
    readonly className?: string | undefined;
}

/**
 * Sends the search once typing pauses. Follows `value` when it changes from elsewhere, such as the back
 * button or clearing the filters, and drops a pending search that the change made stale.
 */
export function SearchField({ value, onSearch, label, placeholder, maxLength, className }: SearchFieldProps) {
    const [draft, setDraft] = useState(value);
    const [followed, setFollowed] = useState(value);
    const latestDraft = useRef(draft);
    const pending = useRef<ReturnType<typeof setTimeout>>(undefined);

    if (value !== followed) {
        setFollowed(value);
        if (value !== draft.trim()) setDraft(value);
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
        <InputGroup addon={<Icon name="search" />} className={className}>
            <Input
                type="search"
                aria-label={label}
                placeholder={placeholder}
                autoComplete="off"
                spellCheck={false}
                maxLength={maxLength}
                value={draft}
                onChange={(event) => {
                    const next = event.currentTarget.value;
                    setDraft(next);
                    latestDraft.current = next;
                    clearTimeout(pending.current);
                    pending.current = setTimeout(() => {
                        if (latestDraft.current !== next) return;
                        const q = next.trim();
                        if (q !== value) onSearch(q);
                    }, SEARCH_DEBOUNCE_MS);
                }}
            />
        </InputGroup>
    );
}
