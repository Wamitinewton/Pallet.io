"use client";

import { useQueryStates } from "nuqs";
import { useCallback } from "react";
import type { InviteListParams, InviteStatusFilter } from "../domain/invite-list-query";
import { inviteSearchParams } from "./search-params";

export interface InviteListParamsState {
    readonly params: InviteListParams;
    /** Shows another status from its first page. */
    readonly showStatus: (status: InviteStatusFilter) => void;
    readonly goToPage: (page: number) => void;
}

export function useInviteListParams(): InviteListParamsState {
    const [params, setParams] = useQueryStates(inviteSearchParams);

    const showStatus = useCallback(
        (inviteStatus: InviteStatusFilter) => {
            void setParams({ inviteStatus, invitePage: null });
        },
        [setParams],
    );

    const goToPage = useCallback(
        (invitePage: number) => {
            void setParams({ invitePage }, { history: "push", scroll: true });
        },
        [setParams],
    );

    return { params, showStatus, goToPage };
}
