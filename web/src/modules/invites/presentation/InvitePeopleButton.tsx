"use client";

import { useOrgAccess } from "@/modules/members";
import { Button, Icon, type ButtonVariant } from "@/shared/presentation/ui";
import { INVITE_PEOPLE_COPY } from "./invite-copy";
import { useInvites } from "./InvitesProvider";

export function InvitePeopleButton({ variant = "primary" }: Readonly<{ variant?: ButtonVariant }>) {
    const access = useOrgAccess();
    const { openInviteDialog } = useInvites();
    if (!access.can("invites.manage")) return null;

    return (
        <Button
            variant={variant}
            onClick={() => {
                openInviteDialog();
            }}
        >
            <Icon name="mail" />
            {INVITE_PEOPLE_COPY}
        </Button>
    );
}
