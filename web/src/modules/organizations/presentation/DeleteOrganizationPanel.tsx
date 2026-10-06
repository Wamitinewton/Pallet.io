"use client";

import { messageFor } from "@/shared/presentation/errors";
import {
    Button,
    Callout,
    ConfirmDialog,
    Icon,
    Panel,
    PanelBody,
    PanelHead,
    Text,
    useToast,
} from "@/shared/presentation/ui";
import type { Route } from "next";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { isConfirmationMismatch } from "../domain/delete-confirmation";
import type { Organization } from "../domain/organization";
import {
    CONFIRMATION_MISMATCH_COPY,
    deleteConsequencesCopy,
    deletedCopy,
    deleteErrorCopy,
    deleteImpactCopy,
    STEP_BACK_COPY,
} from "./organization-copy";
import styles from "./OrgSettingsView.module.css";
import { useDeleteOrganization } from "./use-organization-mutations";

const LANDING_PATH = "/orgs" as Route;

export interface DeleteOrganizationPanelProps {
    readonly organization: Organization;
}

/** Shown only to the owner of a team organization; the backend still decides, including whether to ask for a password. */
export function DeleteOrganizationPanel({ organization }: DeleteOrganizationPanelProps) {
    const router = useRouter();
    const toast = useToast();
    const remove = useDeleteOrganization(organization);
    const [open, setOpen] = useState(false);

    return (
        <Panel tone="danger">
            <PanelHead title="Delete organization" description={deleteImpactCopy(organization.name)} />
            <PanelBody className={styles.danger}>
                <Text tone="muted" size="sm" className={styles.stepBack}>
                    {STEP_BACK_COPY}
                </Text>
                <ConfirmDialog
                    open={open}
                    onOpenChange={(next) => {
                        if (remove.isPending) return;
                        setOpen(next);
                        if (!next) remove.reset();
                    }}
                    trigger={
                        <Button variant="danger-outline">
                            <Icon name="trash" />
                            Delete {organization.name}
                        </Button>
                    }
                    title={`Delete ${organization.name}?`}
                    description={deleteConsequencesCopy(organization.counts)}
                    confirmValue={organization.slug}
                    confirmLabel="Delete organization"
                    loading={remove.isPending}
                    onConfirm={(confirmation) => {
                        remove.mutate(confirmation, {
                            onSuccess: () => {
                                setOpen(false);
                                toast.success(deletedCopy(organization.name));
                                router.replace(LANDING_PATH);
                            },
                        });
                    }}
                >
                    {remove.isError && (
                        <Callout tone="red" role="alert">
                            {isConfirmationMismatch(remove.error)
                                ? CONFIRMATION_MISMATCH_COPY
                                : messageFor(remove.error, deleteErrorCopy)}
                        </Callout>
                    )}
                </ConfirmDialog>
            </PanelBody>
        </Panel>
    );
}
