"use client";

import { ApiError } from "@/shared/domain/errors";
import type { OrgId } from "@/shared/domain/ids";
import { messageFor } from "@/shared/presentation/errors";
import { applyServerErrors } from "@/shared/presentation/forms";
import { Button, Field, Input, Panel, PanelBody, PanelFoot, PanelHead, Text, useToast } from "@/shared/presentation/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm, useWatch } from "react-hook-form";
import { isSameTeamName, renameTeamSchema, type RenameTeamForm, type Team } from "../domain/team";
import {
    NAME_LABEL_COPY,
    NOT_RENAMED_COPY,
    RENAME_TITLE_COPY,
    RENAMED_COPY,
    renameErrorCopy,
    SLUG_STAYS_COPY,
} from "./team-copy";
import styles from "./Teams.module.css";
import { useRenameTeam } from "./use-team-mutations";

export interface RenameTeamPanelProps {
    readonly orgId: OrgId;
    readonly team: Team;
}

export function RenameTeamPanel({ orgId, team }: RenameTeamPanelProps) {
    const toast = useToast();
    const rename = useRenameTeam(orgId, team);
    const form = useForm<RenameTeamForm>({
        resolver: zodResolver(renameTeamSchema),
        defaultValues: { name: team.name },
    });
    const typed = useWatch({ control: form.control, name: "name" });

    const submit = form.handleSubmit((values) => {
        rename.mutate(values, {
            onSuccess: (renamed) => {
                form.reset({ name: renamed.name });
                toast.success(RENAMED_COPY);
            },
            onError: (error) => {
                const unplaced = applyServerErrors(form, error);
                const placedOnFields =
                    error instanceof ApiError && error.fieldErrors.length > 0 && unplaced.length === 0;
                if (!placedOnFields) toast.error(`${NOT_RENAMED_COPY} ${messageFor(error, renameErrorCopy)}`);
            },
        });
    });

    return (
        <Panel>
            <form onSubmit={(event) => void submit(event)} noValidate>
                <PanelHead title={RENAME_TITLE_COPY} />
                <PanelBody>
                    <Field label={NAME_LABEL_COPY} error={form.formState.errors.name?.message}>
                        <Input autoComplete="off" {...form.register("name")} />
                    </Field>
                </PanelBody>
                <PanelFoot className={styles.footSpread}>
                    <span>
                        {SLUG_STAYS_COPY}{" "}
                        <Text mono size="sm">
                            {team.slug}
                        </Text>
                        .
                    </span>
                    <Button
                        type="submit"
                        variant="primary"
                        size="sm"
                        loading={rename.isPending}
                        disabled={isSameTeamName(team, typed)}
                    >
                        Save
                    </Button>
                </PanelFoot>
            </form>
        </Panel>
    );
}
