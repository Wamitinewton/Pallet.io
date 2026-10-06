"use client";

import type { OrgId } from "@/shared/domain/ids";
import { Button, Dialog, DialogClose, DialogFooter } from "@/shared/presentation/ui";
import type { Invite } from "../domain/invite";
import { KEEP_IT_COPY, REVOKE_INVITE_COPY, REVOKE_TITLE_COPY, revokeDescription } from "./invite-copy";
import { useRevokeInvite } from "./use-invite-mutations";

export interface RevokeInviteDialogProps {
    readonly orgId: OrgId;
    /** The invite to revoke; the dialog is open while one is set. */
    readonly invite: Invite | undefined;
    readonly onClose: () => void;
}

/** Closes as soon as it is confirmed: the row leaves the pending list at once and comes back only if refused. */
export function RevokeInviteDialog({ orgId, invite, onClose }: RevokeInviteDialogProps) {
    const revoke = useRevokeInvite(orgId);

    return (
        <Dialog
            open={invite !== undefined}
            onOpenChange={(open) => {
                if (!open) onClose();
            }}
            title={REVOKE_TITLE_COPY}
            description={invite === undefined ? "" : revokeDescription(invite.email)}
            onSubmit={() => {
                if (invite !== undefined) revoke.mutate(invite);
                onClose();
            }}
        >
            <DialogFooter>
                <DialogClose>
                    <Button variant="secondary">{KEEP_IT_COPY}</Button>
                </DialogClose>
                <Button type="submit" variant="danger">
                    {REVOKE_INVITE_COPY}
                </Button>
            </DialogFooter>
        </Dialog>
    );
}
