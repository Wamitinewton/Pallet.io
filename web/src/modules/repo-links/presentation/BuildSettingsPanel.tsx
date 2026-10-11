"use client";

import { useOrgAccess } from "@/modules/members";
import { ApiError } from "@/shared/domain/errors";
import type { OrgId } from "@/shared/domain/ids";
import { messageFor } from "@/shared/presentation/errors";
import { applyServerErrors } from "@/shared/presentation/forms";
import { Button, Callout, Panel, PanelBody, PanelFoot, PanelHead, Stack, useToast } from "@/shared/presentation/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import {
    buildSettingsChange,
    buildSettingsOf,
    buildSettingsSchema,
    changedFields,
    isNoChange,
    type BuildSettingField,
    type BuildSettingsForm,
} from "../domain/build-settings";
import type { BuildSettings, RepoLink } from "../domain/repo-link";
import { BRANCH_NOT_FOUND, INVALID_ROOT_DIRECTORY } from "../domain/repo-link-errors";
import { BuildSettingsFields } from "./BuildSettingsFields";
import {
    branchNotFoundCopy,
    BUILD_SETTINGS_DESCRIPTION_COPY,
    BUILD_SETTINGS_DISCONNECTED_COPY,
    BUILD_SETTINGS_READ_ONLY_COPY,
    BUILD_SETTINGS_TITLE_COPY,
    changedMeanwhileCopy,
    latestValueCopy,
    LINK_GONE_COPY,
    PRODUCTION_BRANCH_HINT_COPY,
    SAVE_CHANGES_COPY,
    SETTINGS_NOT_SAVED_COPY,
    SETTINGS_SAVED_COPY,
    tuneErrorCopy,
} from "./repo-link-copy";
import styles from "./RepoLinks.module.css";
import { useTuneRepoLink } from "./use-repo-link-mutations";

export interface BuildSettingsPanelProps {
    readonly orgId: OrgId;
    /** The link as last read: the base for what counts as a change, and the version sent back. */
    readonly link: RepoLink;
    /** A disconnected link shows its last settings, which can't be changed until it is linked again. */
    readonly active: boolean;
}

/** Which fields someone else's save changed, so their new values can be shown beside the person's edits. */
type ChangedMeanwhile = readonly BuildSettingField[];

function changesNothing(link: RepoLink, values: BuildSettingsForm): boolean {
    const parsed = buildSettingsSchema.safeParse(values);
    return parsed.success && isNoChange(buildSettingsChange(link, parsed.data));
}

function notesFor(fields: ChangedMeanwhile, link: RepoLink): Partial<Record<BuildSettingField, string>> {
    return Object.fromEntries(fields.map((field) => [field, latestValueCopy(field, link[field])]));
}

/**
 * Sends only what changed, with the version last read. The form follows the link as it is read again, except
 * for the fields the person has edited, so a save that lost to someone else's keeps the edits for them to
 * check and save again.
 */
export function BuildSettingsPanel({ orgId, link, active }: BuildSettingsPanelProps) {
    const toast = useToast();
    const canTune = useOrgAccess().can("repoLink.tune");
    const editable = active && canTune;
    const tune = useTuneRepoLink(orgId, link);
    const [changedMeanwhile, setChangedMeanwhile] = useState<ChangedMeanwhile | null>(null);
    const form = useForm<BuildSettingsForm, unknown, BuildSettings>({
        resolver: zodResolver(buildSettingsSchema),
        values: buildSettingsOf(link),
        resetOptions: { keepDirtyValues: true },
    });
    const [productionBranch, rootDirectory, autoDeploy] = useWatch({
        control: form.control,
        name: ["productionBranch", "rootDirectory", "autoDeploy"],
    });

    const submit = form.handleSubmit((settings) => {
        setChangedMeanwhile(null);
        tune.mutate(settings, {
            onSuccess: (outcome) => {
                if (outcome.status === "updated") {
                    form.reset(buildSettingsOf(outcome.link));
                    toast.success(SETTINGS_SAVED_COPY);
                    return;
                }
                if (outcome.latest === null) {
                    toast.error(LINK_GONE_COPY);
                    return;
                }
                setChangedMeanwhile(changedFields(buildSettingsChange(link, outcome.latest)));
            },
            onError: (error) => {
                if (error instanceof ApiError && error.is(BRANCH_NOT_FOUND)) {
                    form.setError(
                        "productionBranch",
                        { type: "server", message: branchNotFoundCopy(settings.productionBranch) },
                        { shouldFocus: true },
                    );
                    return;
                }
                if (error instanceof ApiError && error.is(INVALID_ROOT_DIRECTORY)) {
                    form.setError("rootDirectory", { type: "server", message: error.message }, { shouldFocus: true });
                    return;
                }
                const unplaced = applyServerErrors(form, error);
                const placed = error instanceof ApiError && error.fieldErrors.length > 0 && unplaced.length === 0;
                if (!placed) toast.error(`${SETTINGS_NOT_SAVED_COPY} ${messageFor(error, tuneErrorCopy)}`);
            },
        });
    });

    const footNote = !active ? BUILD_SETTINGS_DISCONNECTED_COPY : canTune ? undefined : BUILD_SETTINGS_READ_ONLY_COPY;

    return (
        <Panel aria-label={BUILD_SETTINGS_TITLE_COPY}>
            <form onSubmit={(event) => void submit(event)} noValidate>
                <PanelHead title={BUILD_SETTINGS_TITLE_COPY} description={BUILD_SETTINGS_DESCRIPTION_COPY} />
                <PanelBody>
                    <Stack>
                        {changedMeanwhile !== null && (
                            <Callout tone="amber" role="alert">
                                {changedMeanwhileCopy(changedMeanwhile)}
                            </Callout>
                        )}
                        <BuildSettingsFields
                            form={form}
                            branchHint={PRODUCTION_BRANCH_HINT_COPY}
                            disabled={!editable}
                            notes={changedMeanwhile === null ? {} : notesFor(changedMeanwhile, link)}
                        />
                    </Stack>
                </PanelBody>
                {(editable || footNote !== undefined) && (
                    <PanelFoot className={styles.footSpread}>
                        <span>{footNote}</span>
                        {editable && (
                            <Button
                                type="submit"
                                variant="primary"
                                size="sm"
                                loading={tune.isPending}
                                disabled={changesNothing(link, { productionBranch, rootDirectory, autoDeploy })}
                            >
                                {SAVE_CHANGES_COPY}
                            </Button>
                        )}
                    </PanelFoot>
                )}
            </form>
        </Panel>
    );
}
