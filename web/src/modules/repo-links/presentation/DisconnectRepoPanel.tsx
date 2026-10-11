"use client";

import { ApiError } from "@/shared/domain/errors";
import type { OrgId } from "@/shared/domain/ids";
import { messageFor } from "@/shared/presentation/errors";
import {
    Button,
    Callout,
    Dialog,
    DialogBody,
    DialogClose,
    DialogFooter,
    Icon,
    Panel,
    PanelBody,
    PanelHead,
    useToast,
} from "@/shared/presentation/ui";
import { useState } from "react";
import type { RepoLink } from "../domain/repo-link";
import { REPO_LINK_NOT_FOUND } from "../domain/repo-link-errors";
import {
    ALREADY_UNLINKED_COPY,
    DISCONNECT_DESCRIPTION_COPY,
    DISCONNECT_TITLE_COPY,
    disconnectConfirmDescription,
    disconnectConfirmTitle,
    DISCONNECTED_TOAST_COPY,
    disconnectErrorCopy,
    KEEP_LINKED_COPY,
} from "./repo-link-copy";
import styles from "./RepoLinks.module.css";
import { useDisconnectRepoLink } from "./use-repo-link-mutations";

export interface DisconnectRepoPanelProps {
    readonly orgId: OrgId;
    readonly link: RepoLink;
    /** Undefined while the app is read; the confirmation then names the repository alone. */
    readonly appName: string | undefined;
}

export function DisconnectRepoPanel({ orgId, link, appName }: DisconnectRepoPanelProps) {
    const toast = useToast();
    const disconnect = useDisconnectRepoLink(orgId, link.appId);
    const [open, setOpen] = useState(false);

    return (
        <Panel tone="danger" aria-label={DISCONNECT_TITLE_COPY}>
            <PanelHead title={DISCONNECT_TITLE_COPY} />
            <PanelBody>
                <p className={styles.explanation}>{DISCONNECT_DESCRIPTION_COPY}</p>
                <Dialog
                    open={open}
                    onOpenChange={(next) => {
                        if (disconnect.isPending) return;
                        setOpen(next);
                        if (!next) disconnect.reset();
                    }}
                    trigger={
                        <Button variant="danger-outline" size="sm">
                            <Icon name="unlink" />
                            {DISCONNECT_TITLE_COPY}
                        </Button>
                    }
                    title={disconnectConfirmTitle(link.repoFullName)}
                    description={disconnectConfirmDescription(link.productionBranch, appName ?? link.repoFullName)}
                    onSubmit={() => {
                        if (disconnect.isPending) return;
                        disconnect.mutate(undefined, {
                            onSuccess: () => {
                                setOpen(false);
                                toast.success(DISCONNECTED_TOAST_COPY);
                            },
                            onError: (error) => {
                                if (!(error instanceof ApiError && error.is(REPO_LINK_NOT_FOUND))) return;
                                setOpen(false);
                                toast.info(ALREADY_UNLINKED_COPY);
                            },
                        });
                    }}
                >
                    {disconnect.isError && (
                        <DialogBody>
                            <Callout tone="red" role="alert">
                                {messageFor(disconnect.error, disconnectErrorCopy)}
                            </Callout>
                        </DialogBody>
                    )}
                    <DialogFooter>
                        <DialogClose>
                            <Button variant="secondary" disabled={disconnect.isPending}>
                                {KEEP_LINKED_COPY}
                            </Button>
                        </DialogClose>
                        <Button type="submit" variant="danger" loading={disconnect.isPending}>
                            {DISCONNECT_TITLE_COPY}
                        </Button>
                    </DialogFooter>
                </Dialog>
            </PanelBody>
        </Panel>
    );
}
