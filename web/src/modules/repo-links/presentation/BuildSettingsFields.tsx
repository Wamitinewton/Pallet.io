"use client";

import { Field, FieldGrid, Hint, Input, Label, Stack, Switch } from "@/shared/presentation/ui";
import { useId } from "react";
import { Controller, type UseFormReturn } from "react-hook-form";
import type { BuildSettingField, BuildSettingsForm } from "../domain/build-settings";
import type { BuildSettings } from "../domain/repo-link";
import { MAX_ROOT_DIRECTORY_LENGTH } from "../domain/root-directory";
import {
    AUTO_DEPLOY_HINT_COPY,
    AUTO_DEPLOY_LABEL_COPY,
    PRODUCTION_BRANCH_LABEL_COPY,
    ROOT_DIRECTORY_HINT_COPY,
    ROOT_DIRECTORY_LABEL_COPY,
    ROOT_DIRECTORY_PLACEHOLDER_COPY,
} from "./repo-link-copy";
import styles from "./RepoLinks.module.css";

export interface BuildSettingsFieldsProps {
    readonly form: UseFormReturn<BuildSettingsForm, unknown, BuildSettings>;
    readonly branchHint: string;
    readonly disabled?: boolean;
    /** Said in place of a field's hint, such as the value someone else just saved. */
    readonly notes?: Partial<Record<BuildSettingField, string>>;
}

const codeInput = { mono: true, spellCheck: false, autoComplete: "off", autoCapitalize: "off" } as const;

/** Shared by linking a repository and the settings panel, so both ask for the same thing the same way. */
export function BuildSettingsFields({ form, branchHint, disabled = false, notes = {} }: BuildSettingsFieldsProps) {
    const autoDeployId = useId();
    const { errors } = form.formState;

    return (
        <fieldset className={styles.fieldset} disabled={disabled}>
            <Stack>
                <FieldGrid>
                    <Field
                        label={PRODUCTION_BRANCH_LABEL_COPY}
                        hint={notes.productionBranch ?? branchHint}
                        error={errors.productionBranch?.message}
                    >
                        <Input {...codeInput} {...form.register("productionBranch")} />
                    </Field>
                    <Field
                        label={ROOT_DIRECTORY_LABEL_COPY}
                        hint={notes.rootDirectory ?? ROOT_DIRECTORY_HINT_COPY}
                        error={errors.rootDirectory?.message}
                    >
                        <Input
                            {...codeInput}
                            placeholder={ROOT_DIRECTORY_PLACEHOLDER_COPY}
                            maxLength={MAX_ROOT_DIRECTORY_LENGTH}
                            {...form.register("rootDirectory")}
                        />
                    </Field>
                </FieldGrid>
                <div className={styles.settingRow}>
                    <div>
                        <Label htmlFor={autoDeployId}>{AUTO_DEPLOY_LABEL_COPY}</Label>
                        <Hint id={`${autoDeployId}-hint`} className={styles.settingHint}>
                            {notes.autoDeploy ?? AUTO_DEPLOY_HINT_COPY}
                        </Hint>
                    </div>
                    <Controller
                        control={form.control}
                        name="autoDeploy"
                        render={({ field: { value, onChange, onBlur, ref, name } }) => (
                            <Switch
                                id={autoDeployId}
                                ref={ref}
                                name={name}
                                checked={value}
                                onCheckedChange={onChange}
                                onBlur={onBlur}
                                aria-describedby={`${autoDeployId}-hint`}
                            />
                        )}
                    />
                </div>
            </Stack>
        </fieldset>
    );
}
