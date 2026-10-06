"use client";

import type { Team } from "@/modules/teams";
import { ApiError } from "@/shared/domain/errors";
import type { OrgId } from "@/shared/domain/ids";
import { messageFor } from "@/shared/presentation/errors";
import { applyServerErrors } from "@/shared/presentation/forms";
import {
    Button,
    Callout,
    Field,
    FieldGrid,
    Input,
    Panel,
    PanelBody,
    PanelFoot,
    PanelHead,
    Select,
    Stack,
    useToast,
} from "@/shared/presentation/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { Controller, useForm, useWatch } from "react-hook-form";
import type { App } from "../domain/app";
import { TEAM_NOT_FOUND } from "../domain/app-errors";
import {
    appChange,
    appSettingsOf,
    appSettingsSchema,
    isNoChange,
    type AppSettings,
    type AppSettingsForm,
} from "../domain/update-app";
import {
    APP_NAME_LABEL_COPY,
    APP_UPDATED_COPY,
    APPLIES_RIGHT_AWAY_COPY,
    CONCURRENT_MODIFICATION_COPY,
    GENERAL_DESCRIPTION_COPY,
    GENERAL_TITLE_COPY,
    LOADING_TEAMS_COPY,
    NO_TEAM_COPY,
    NOT_UPDATED_COPY,
    TEAM_GONE_COPY,
    TEAM_LABEL_COPY,
    UNKNOWN_TEAM_COPY,
    updateErrorCopy,
} from "./app-copy";
import styles from "./Apps.module.css";
import { useUpdateApp } from "./use-app-mutations";

export interface AppSettingsPanelProps {
    readonly orgId: OrgId;
    readonly app: App;
    /** Undefined while the organization's teams are read. */
    readonly teams: readonly Team[] | undefined;
}

/** Nothing to save until a field differs from the app as last read; the button says so by staying disabled. */
function changesNothing(app: App, values: AppSettingsForm): boolean {
    const parsed = appSettingsSchema.safeParse(values);
    return parsed.success && isNoChange(appChange(app, parsed.data));
}

export function AppSettingsPanel({ orgId, app, teams }: AppSettingsPanelProps) {
    const toast = useToast();
    const update = useUpdateApp(orgId, app);
    const [changedMeanwhile, setChangedMeanwhile] = useState(false);
    const form = useForm<AppSettingsForm, unknown, AppSettings>({
        resolver: zodResolver(appSettingsSchema),
        defaultValues: appSettingsOf(app),
    });
    const [name, teamId] = useWatch({ control: form.control, name: ["name", "teamId"] });
    const currentTeamListed = teams?.some((team) => team.id === app.teamId) === true;

    const submit = form.handleSubmit((settings) => {
        setChangedMeanwhile(false);
        update.mutate(settings, {
            onSuccess: (outcome) => {
                if (outcome.status === "changedMeanwhile") {
                    form.reset(appSettingsOf(outcome.latest));
                    setChangedMeanwhile(true);
                    return;
                }
                form.reset(appSettingsOf(outcome.app));
                toast.success(APP_UPDATED_COPY);
            },
            onError: (error) => {
                if (error instanceof ApiError && error.is(TEAM_NOT_FOUND)) {
                    form.setError("teamId", { type: "server", message: TEAM_GONE_COPY }, { shouldFocus: true });
                    return;
                }
                const unplaced = applyServerErrors(form, error);
                const placedOnFields =
                    error instanceof ApiError && error.fieldErrors.length > 0 && unplaced.length === 0;
                if (!placedOnFields) toast.error(`${NOT_UPDATED_COPY} ${messageFor(error, updateErrorCopy)}`);
            },
        });
    });

    return (
        <Panel aria-label={GENERAL_TITLE_COPY}>
            <form onSubmit={(event) => void submit(event)} noValidate>
                <PanelHead title={GENERAL_TITLE_COPY} description={GENERAL_DESCRIPTION_COPY} />
                <PanelBody>
                    <Stack>
                        {changedMeanwhile && (
                            <Callout tone="amber" role="alert">
                                {CONCURRENT_MODIFICATION_COPY}
                            </Callout>
                        )}
                        <FieldGrid>
                            <Field label={APP_NAME_LABEL_COPY} error={form.formState.errors.name?.message}>
                                <Input autoComplete="off" {...form.register("name")} />
                            </Field>
                            <Field label={TEAM_LABEL_COPY} error={form.formState.errors.teamId?.message}>
                                <Controller
                                    control={form.control}
                                    name="teamId"
                                    render={({ field }) => (
                                        <Select {...field} value={field.value ?? ""}>
                                            <option value="">{NO_TEAM_COPY}</option>
                                            {app.teamId !== null && !currentTeamListed && (
                                                <option value={app.teamId}>
                                                    {teams === undefined ? LOADING_TEAMS_COPY : UNKNOWN_TEAM_COPY}
                                                </option>
                                            )}
                                            {teams?.map((team) => (
                                                <option key={team.id} value={team.id}>
                                                    {team.name}
                                                </option>
                                            ))}
                                        </Select>
                                    )}
                                />
                            </Field>
                        </FieldGrid>
                    </Stack>
                </PanelBody>
                <PanelFoot className={styles.footSpread}>
                    <span>{APPLIES_RIGHT_AWAY_COPY}</span>
                    <Button
                        type="submit"
                        variant="primary"
                        size="sm"
                        loading={update.isPending}
                        disabled={changesNothing(app, { name, teamId })}
                    >
                        Save
                    </Button>
                </PanelFoot>
            </form>
        </Panel>
    );
}
