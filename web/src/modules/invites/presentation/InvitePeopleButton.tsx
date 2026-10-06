"use client";

import { useOrgAccess } from "@/modules/members";
import { Button, Icon } from "@/shared/presentation/ui";
import { INVITE_PEOPLE_COPY } from "./invite-copy";
import { useInvites } from "./InvitesProvider";

export function InvitePeopleButton() {
    const access = useOrgAccess();
    const { openInviteDialog } = useInvites();
    if (!access.can("invites.manage")) return null;

    return (
        <Button
            variant="primary"
            onClick={() => {
                openInviteDialog();
            }}
        >
            <Icon name="mail" />
            {INVITE_PEOPLE_COPY}
        </Button>
    );
}
