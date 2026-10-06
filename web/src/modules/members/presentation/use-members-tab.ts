"use client";

import { useQueryState } from "nuqs";
import { membersTabParam, type MembersPageTab } from "./search-params";

export function useMembersTab(): readonly [MembersPageTab, (tab: MembersPageTab) => void] {
    const [tab, setTab] = useQueryState("tab", membersTabParam);
    return [
        tab,
        (next) => {
            void setTab(next);
        },
    ];
}
