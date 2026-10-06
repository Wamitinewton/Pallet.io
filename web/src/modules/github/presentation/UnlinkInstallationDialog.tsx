"use client";

import { ApiError } from "@/shared/domain/errors";
import type { OrgId } from "@/shared/domain/ids";
import { messageFor } from "@/shared/presentation/errors";
import { Button, Callout, Dialog, DialogBody, DialogClose, DialogFooter, useToast } from "@/shared/presentation/ui";
import { INSTALLATION_NOT_FOUND } from "../domain/github-errors";
import type { InstallationLink } from "../domain/installation";
import {
    alreadyUnlinkedCopy,
    UNLINK_COPY,
    unlinkDescription,
    unlinkedCopy,
    unlinkErrorCopy,
    unlinkTitle,
} from "./github-copy";
import { useUnlinkInstallation } from "./use-github-mutations";

export interface UnlinkInstallationDialogProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    /** The installation to unlink; the dialog is open while one is set. */
    readonly installation: InstallationLink | undefined;
    readonly onClose: () => void;
}

export function UnlinkInstallationDialog({ orgId, orgName, installation, onClose }: UnlinkInstallationDialogProps) {
    const toast = useToast();
    const unlink = useUnlinkInstallation(orgId);
    const login = installation?.accountLogin ?? "";

    const close = () => {
        unlink.reset();
        onClose();
    };

    const confirm = () => {
        if (installation === undefined || unlink.isPending) return;
        unlink.mutate(installation.installationId, {
            onSuccess: () => {
                close();
                toast.success(unlinkedCopy(login, orgName));
            },
            onError: (error) => {
                if (!(error instanceof ApiError && error.is(INSTALLATION_NOT_FOUND))) return;
                close();
                toast.info(alreadyUnlinkedCopy(login, orgName));
            },
        });
    };

    return (
        <Dialog
            open={installation !== undefined}
            onOpenChange={(open) => {
                if (!open && !unlink.isPending) close();
            }}
            title={unlinkTitle(login)}
            description={unlinkDescription(orgName)}
            onSubmit={confirm}
        >
            {unlink.isError && (
                <DialogBody>
                    <Callout tone="red" role="alert">
                        {messageFor(unlink.error, unlinkErrorCopy)}
                    </Callout>
                </DialogBody>
            )}
            <DialogFooter>
                <DialogClose>
                    <Button variant="secondary" disabled={unlink.isPending}>
                        Cancel
                    </Button>
                </DialogClose>
                <Button type="submit" variant="danger" loading={unlink.isPending}>
                    {UNLINK_COPY}
                </Button>
            </DialogFooter>
        </Dialog>
    );
}
