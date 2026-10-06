"use client";

import { ApiError } from "@/shared/domain/errors";
import type { OrgId } from "@/shared/domain/ids";
import { messageFor } from "@/shared/presentation/errors";
import { Button, Callout, Dialog, DialogBody, DialogClose, DialogFooter, useToast } from "@/shared/presentation/ui";
import type { Route } from "next";
import { useRouter } from "next/navigation";
import type { Member } from "../domain/member";
import { MEMBER_NOT_FOUND } from "../domain/member-errors";
import {
    alreadyRemovedCopy,
    leaveDescription,
    leaveErrorCopy,
    leaveTitle,
    leftCopy,
    removedCopy,
    removeDescription,
    removeErrorCopy,
    removeTitle,
} from "./member-copy";
import { useLeaveOrganization, useRemoveMember } from "./use-member-mutations";

const LANDING_PATH = "/orgs" as Route;

export interface RemoveMemberDialogProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    /** The member to remove; the dialog is open while one is set. */
    readonly member: Member | undefined;
    /** The member is the caller, so removing them is leaving the organization. */
    readonly leaving: boolean;
    readonly onClose: () => void;
}

export function RemoveMemberDialog({ orgId, orgName, member, leaving, onClose }: RemoveMemberDialogProps) {
    const toast = useToast();
    const router = useRouter();
    const remove = useRemoveMember(orgId);
    const leave = useLeaveOrganization(orgId);
    const pending = remove.isPending || leave.isPending;
    const name = member?.displayName ?? "";

    const close = () => {
        remove.reset();
        leave.reset();
        onClose();
    };

    const confirm = () => {
        if (member === undefined || pending) return;
        if (leaving) {
            leave.mutate(member.userId, {
                onSuccess: () => {
                    close();
                    toast.success(leftCopy(orgName));
                    router.replace(LANDING_PATH);
                },
            });
            return;
        }
        remove.mutate(member, {
            onSuccess: () => {
                close();
                toast.success(removedCopy(name, orgName));
            },
            onError: (error) => {
                if (!(error instanceof ApiError && error.is(MEMBER_NOT_FOUND))) return;
                close();
                toast.info(alreadyRemovedCopy(name));
            },
        });
    };

    const error = leaving
        ? leave.isError && messageFor(leave.error, leaveErrorCopy)
        : remove.isError && messageFor(remove.error, removeErrorCopy);

    return (
        <Dialog
            open={member !== undefined}
            onOpenChange={(open) => {
                if (!open && !pending) close();
            }}
            title={leaving ? leaveTitle(orgName) : removeTitle(name)}
            description={leaving ? leaveDescription(orgName) : removeDescription(orgName)}
            onSubmit={confirm}
        >
            {error !== false && (
                <DialogBody>
                    <Callout tone="red" role="alert">
                        {error}
                    </Callout>
                </DialogBody>
            )}
            <DialogFooter>
                <DialogClose>
                    <Button variant="secondary" disabled={pending}>
                        Cancel
                    </Button>
                </DialogClose>
                <Button type="submit" variant="danger" loading={pending}>
                    {leaving ? "Leave organization" : "Remove member"}
                </Button>
            </DialogFooter>
        </Dialog>
    );
}
