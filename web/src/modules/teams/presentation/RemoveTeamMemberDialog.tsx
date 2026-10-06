"use client";

import { Button, Dialog, DialogClose, DialogFooter } from "@/shared/presentation/ui";
import type { TeamMember } from "../domain/team";
import { REMOVE_FROM_TEAM_COPY, removeDescription, removeTitle } from "./team-copy";

export interface RemoveTeamMemberDialogProps {
    readonly teamName: string;
    readonly orgName: string;
    /** The person to take off the team; the dialog is open while one is set. */
    readonly member: TeamMember | undefined;
    readonly onConfirm: (member: TeamMember) => void;
    readonly onClose: () => void;
}

/** Closes on confirm: the removal shows at once and reports its own outcome. */
export function RemoveTeamMemberDialog({ teamName, orgName, member, onConfirm, onClose }: RemoveTeamMemberDialogProps) {
    const name = member?.displayName ?? "";

    return (
        <Dialog
            open={member !== undefined}
            onOpenChange={(open) => {
                if (!open) onClose();
            }}
            title={removeTitle(name, teamName)}
            description={removeDescription(name, orgName)}
            onSubmit={() => {
                if (member === undefined) return;
                onConfirm(member);
                onClose();
            }}
        >
            <DialogFooter>
                <DialogClose>
                    <Button variant="secondary">Cancel</Button>
                </DialogClose>
                <Button type="submit" variant="danger">
                    {REMOVE_FROM_TEAM_COPY}
                </Button>
            </DialogFooter>
        </Dialog>
    );
}
