"use client";

import type { OrgId, UserId } from "@/shared/domain/ids";
import type { OrgKind } from "@/shared/domain/org-kind";
import { can as permits, type Capability } from "@/shared/domain/permissions";
import type { Role } from "@/shared/domain/role";
import { useQuery } from "@tanstack/react-query";
import { createContext, use, type ReactNode } from "react";
import { useMemberUseCases } from "./member-use-cases";
import { memberQueries } from "./queries";

export interface OrgAccess {
    readonly orgId: OrgId;
    readonly orgKind: OrgKind;
    /** Undefined until `members/me` answers, like `role`. */
    readonly userId: UserId | undefined;
    /** Undefined until `members/me` answers; every capability is denied until then. */
    readonly role: Role | undefined;
    can(capability: Capability): boolean;
}

const OrgAccessContext = createContext<OrgAccess | null>(null);

export interface OrgAccessProviderProps {
    readonly orgId: OrgId;
    /** Fixed at creation, so the layout passes what it already read rather than this module reading it again. */
    readonly orgKind: OrgKind;
    readonly children: ReactNode;
}

export function OrgAccessProvider({ orgId, orgKind, children }: OrgAccessProviderProps) {
    const { getMyMembership } = useMemberUseCases();
    const me = useQuery(memberQueries.mine(getMyMembership, orgId)).data;
    const role = me?.role;

    const access: OrgAccess = {
        orgId,
        orgKind,
        userId: me?.userId,
        role,
        can: (capability) => role !== undefined && permits({ role, orgKind }, capability),
    };

    return <OrgAccessContext value={access}>{children}</OrgAccessContext>;
}

/** The open organization and what the caller may do in it: the only way a screen decides to show a control. */
export function useOrgAccess(): OrgAccess {
    const access = use(OrgAccessContext);
    if (access === null) throw new Error("useOrgAccess() must be called inside <OrgAccessProvider>");
    return access;
}
