"use client";

import { useLocationHash } from "@/shared/presentation/hooks";
import { useCallback, useState } from "react";

export const ACCOUNT_TABS = ["profile", "security"] as const;

export type AccountTab = (typeof ACCOUNT_TABS)[number];

export const SECURITY_SECTION_ID = "security";

function tabFor(hash: string): AccountTab {
    return hash === SECURITY_SECTION_ID ? "security" : "profile";
}

export function isAccountTab(value: string): value is AccountTab {
    return (ACCOUNT_TABS as readonly string[]).includes(value);
}

export interface AccountTabState {
    readonly tab: AccountTab;
    readonly select: (tab: AccountTab) => void;
    /** Set when `#security` arrived from outside the tabs, such as the user menu, and the section should scroll in. */
    readonly scrollToSecurity: boolean;
    readonly scrolledToSecurity: () => void;
}

/** The selected tab follows `#security`, including the user menu's link while already on the page. */
export function useAccountTab(): AccountTabState {
    const hash = useLocationHash();
    const [followed, setFollowed] = useState(hash);
    const [tab, setTab] = useState<AccountTab>(() => tabFor(hash));
    const [scrollToSecurity, setScrollToSecurity] = useState(false);

    if (hash !== followed) {
        setFollowed(hash);
        setTab(tabFor(hash));
        setScrollToSecurity(hash === SECURITY_SECTION_ID);
    }

    const select = useCallback((next: AccountTab) => {
        setTab(next);
        const { pathname, search } = window.location;
        window.history.replaceState(null, "", next === "security" ? `#${SECURITY_SECTION_ID}` : `${pathname}${search}`);
    }, []);

    const scrolledToSecurity = useCallback(() => {
        setScrollToSecurity(false);
    }, []);

    return { tab, select, scrollToSecurity, scrolledToSecurity };
}
