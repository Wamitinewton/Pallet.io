"use client";

import { useOrgAccess } from "@/modules/members";
import type { OrgId } from "@/shared/domain/ids";
import { createContext, use, useState, type ReactNode } from "react";
import { InviteDialog, type InviteDraft } from "./InviteDialog";

interface InvitesContextValue {
    readonly orgId: OrgId;
    readonly orgName: string;
    /** Opens the invite dialog, filled in from `draft` when inviting someone again. */
    readonly openInviteDialog: (draft?: InviteDraft) => void;
}

const InvitesContext = createContext<InvitesContextValue | null>(null);

export interface InvitesProviderProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    readonly children: ReactNode;
}

interface OpenDialog {
    readonly draft: InviteDraft | undefined;
}

/** One invite dialog for the whole page, so the button beside the title and a row's "Invite again" share it. */
export function InvitesProvider({ orgId, orgName, children }: InvitesProviderProps) {
    const access = useOrgAccess();
    const [dialog, setDialog] = useState<OpenDialog>();

    const value: InvitesContextValue = {
        orgId,
        orgName,
        openInviteDialog: (draft) => {
            setDialog({ draft });
        },
    };

    return (
        <InvitesContext value={value}>
            {children}
            {access.role !== undefined && access.can("invites.manage") && (
                <InviteDialog
                    orgId={orgId}
                    orgName={orgName}
                    callerRole={access.role}
                    open={dialog !== undefined}
                    draft={dialog?.draft}
                    onOpenChange={(open) => {
                        if (!open) setDialog(undefined);
                    }}
                />
            )}
        </InvitesContext>
    );
}

export function useInvites(): InvitesContextValue {
    const invites = use(InvitesContext);
    if (invites === null) throw new Error("useInvites() must be called inside <InvitesProvider>");
    return invites;
}
