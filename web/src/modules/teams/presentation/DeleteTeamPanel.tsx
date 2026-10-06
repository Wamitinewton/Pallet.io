"use client";

import type { OrgId } from "@/shared/domain/ids";
import { messageFor } from "@/shared/presentation/errors";
import { Button, Callout, ConfirmDialog, Icon, Panel, PanelBody, PanelHead, useToast } from "@/shared/presentation/ui";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { TeamDeletionNotConfirmedError, type Team } from "../domain/team";
import {
    DELETE_TEAM_COPY,
    DELETE_TITLE_COPY,
    deleteConfirmDescription,
    deleteConfirmTitle,
    deleteErrorCopy,
    deleteImpactCopy,
    deleteTeamLabel,
    DELETION_MISMATCH_COPY,
    teamDeletedCopy,
} from "./team-copy";
import { teamsPath } from "./team-paths";
import styles from "./Teams.module.css";
import { useDeleteTeam } from "./use-team-mutations";

export interface DeleteTeamPanelProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    readonly team: Team;
}

/** Deleting a team takes access from nobody, so the confirmation is the team's name rather than a password. */
export function DeleteTeamPanel({ orgId, orgName, team }: DeleteTeamPanelProps) {
    const router = useRouter();
    const toast = useToast();
    const remove = useDeleteTeam(orgId, team);
    const [open, setOpen] = useState(false);

    return (
        <Panel tone="danger">
            <PanelHead title={DELETE_TITLE_COPY} description={deleteImpactCopy(team.memberCount, orgName)} />
            <PanelBody className={styles.danger}>
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
                            {deleteTeamLabel(team.name)}
                        </Button>
                    }
                    title={deleteConfirmTitle(team.name)}
                    description={deleteConfirmDescription(team.memberCount, orgName)}
                    confirmValue={team.name}
                    confirmLabel={DELETE_TEAM_COPY}
                    loading={remove.isPending}
                    onConfirm={(confirmation) => {
                        remove.mutate(confirmation, {
                            onSuccess: () => {
                                setOpen(false);
                                toast.success(teamDeletedCopy(team.name));
                                router.replace(teamsPath(orgId));
                            },
                        });
                    }}
                >
                    {remove.isError && (
                        <Callout tone="red" role="alert">
                            {remove.error instanceof TeamDeletionNotConfirmedError
                                ? DELETION_MISMATCH_COPY
                                : messageFor(remove.error, deleteErrorCopy)}
                        </Callout>
                    )}
                </ConfirmDialog>
            </PanelBody>
        </Panel>
    );
}
