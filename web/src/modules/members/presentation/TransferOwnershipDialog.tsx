"use client";

import type { OrgId } from "@/shared/domain/ids";
import { messageFor } from "@/shared/presentation/errors";
import {
    Button,
    Callout,
    Dialog,
    DialogBody,
    DialogClose,
    DialogFooter,
    Stack,
    useToast,
} from "@/shared/presentation/ui";
import type { Member } from "../domain/member";
import {
    TRANSFER_STEP_UP_COPY,
    transferDescription,
    transferErrorCopy,
    transferredCopy,
    transferTitle,
} from "./member-copy";
import { useTransferOwnership } from "./use-member-mutations";

export interface TransferOwnershipDialogProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    /** The member to hand ownership to; the dialog is open while one is set. */
    readonly member: Member | undefined;
    readonly onClose: () => void;
}

/** The backend decides whether a password is needed; the prompt comes from step-up only when it does. */
export function TransferOwnershipDialog({ orgId, orgName, member, onClose }: TransferOwnershipDialogProps) {
    const toast = useToast();
    const transfer = useTransferOwnership(orgId);
    const name = member?.displayName ?? "";

    const close = () => {
        transfer.reset();
        onClose();
    };

    return (
        <Dialog
            open={member !== undefined}
            onOpenChange={(open) => {
                if (!open && !transfer.isPending) close();
            }}
            title={transferTitle(name)}
            description={transferDescription(name)}
            onSubmit={() => {
                if (member === undefined || transfer.isPending) return;
                transfer.mutate(member, {
                    onSuccess: () => {
                        close();
                        toast.success(transferredCopy(name, orgName));
                    },
                });
            }}
        >
            <DialogBody>
                <Stack>
                    {transfer.isError && (
                        <Callout tone="red" role="alert">
                            {messageFor(transfer.error, transferErrorCopy(name, orgName))}
                        </Callout>
                    )}
                    <Callout tone="blue" icon="lock">
                        {TRANSFER_STEP_UP_COPY}
                    </Callout>
                </Stack>
            </DialogBody>
            <DialogFooter>
                <DialogClose>
                    <Button variant="secondary" disabled={transfer.isPending}>
                        Cancel
                    </Button>
                </DialogClose>
                <Button type="submit" variant="danger" loading={transfer.isPending}>
                    Transfer ownership
                </Button>
            </DialogFooter>
        </Dialog>
    );
}
