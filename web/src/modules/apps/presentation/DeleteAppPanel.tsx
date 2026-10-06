"use client";

import type { OrgId } from "@/shared/domain/ids";
import { messageFor } from "@/shared/presentation/errors";
import { Button, Callout, ConfirmDialog, Icon, Panel, PanelBody, PanelHead, useToast } from "@/shared/presentation/ui";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { AppDeletionNotConfirmedError, type App } from "../domain/app";
import {
    appDeletedCopy,
    DELETE_APP_COPY,
    DELETE_DESCRIPTION_COPY,
    DELETE_TITLE_COPY,
    deleteAppLabel,
    deleteConfirmDescription,
    deleteConfirmTitle,
    deleteErrorCopy,
    DELETION_MISMATCH_COPY,
} from "./app-copy";
import { appsPath } from "./app-paths";
import { useDeleteApp } from "./use-app-mutations";

export interface DeleteAppPanelProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    readonly app: App;
}

/** Confirmed by typing the slug, the one name that is the app's alone and never changes. */
export function DeleteAppPanel({ orgId, orgName, app }: DeleteAppPanelProps) {
    const router = useRouter();
    const toast = useToast();
    const remove = useDeleteApp(orgId, app);
    const [open, setOpen] = useState(false);

    return (
        <Panel tone="danger" aria-label={DELETE_TITLE_COPY}>
            <PanelHead title={DELETE_TITLE_COPY} description={DELETE_DESCRIPTION_COPY} />
            <PanelBody>
                <ConfirmDialog
                    open={open}
                    onOpenChange={(next) => {
                        if (remove.isPending) return;
                        setOpen(next);
                        if (!next) remove.reset();
                    }}
                    trigger={
                        <Button variant="danger-outline" size="sm">
                            <Icon name="trash" />
                            {deleteAppLabel(app.name)}
                        </Button>
                    }
                    title={deleteConfirmTitle(app.name)}
                    description={deleteConfirmDescription(orgName)}
                    confirmValue={app.slug}
                    confirmLabel={DELETE_APP_COPY}
                    loading={remove.isPending}
                    onConfirm={(confirmation) => {
                        remove.mutate(confirmation, {
                            onSuccess: () => {
                                setOpen(false);
                                toast.success(appDeletedCopy(app.name));
                                router.replace(appsPath(orgId));
                            },
                        });
                    }}
                >
                    {remove.isError && (
                        <Callout tone="red" role="alert">
                            {remove.error instanceof AppDeletionNotConfirmedError
                                ? DELETION_MISMATCH_COPY
                                : messageFor(remove.error, deleteErrorCopy)}
                        </Callout>
                    )}
                </ConfirmDialog>
            </PanelBody>
        </Panel>
    );
}
