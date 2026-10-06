"use client";

import { useTeamDirectory } from "@/modules/teams";
import { ApiError } from "@/shared/domain/errors";
import type { OrgId } from "@/shared/domain/ids";
import { slugFromName } from "@/shared/domain/slug";
import { messageFor } from "@/shared/presentation/errors";
import { applyServerErrors } from "@/shared/presentation/forms";
import {
    Button,
    Callout,
    Choice,
    ChoiceGroup,
    Dialog,
    DialogBody,
    DialogClose,
    DialogFooter,
    Field,
    FieldGrid,
    Input,
    Select,
    Stack,
    useToast,
} from "@/shared/presentation/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { Controller, useForm, useWatch } from "react-hook-form";
import { newAppSchema, type NewApp, type NewAppForm } from "../domain/app";
import { INVALID_REGION, SLUG_TAKEN, TEAM_NOT_FOUND } from "../domain/app-errors";
import { CLOUD_PROVIDERS, isCloudProvider, isRegionOf, regionsFor, type CloudProvider } from "../domain/region-catalog";
import {
    appCreatedCopy,
    CLOUD_LABEL_COPY,
    CREATE_APP_COPY,
    createErrorCopy,
    FIXED_AT_CREATION_CALLOUT_COPY,
    INVALID_REGION_COPY,
    NAME_LABEL_COPY,
    NEW_APP_COPY,
    NEW_APP_DESCRIPTION_COPY,
    NO_TEAM_COPY,
    OPTIONAL_COPY,
    PROVIDER_NAME,
    REGION_HINT_COPY,
    REGION_LABEL_COPY,
    REGION_PLACEHOLDER_COPY,
    regionCountCopy,
    regionLabel,
    SLUG_LABEL_COPY,
    SLUG_TAKEN_COPY,
    slugHintCopy,
    TEAM_GONE_COPY,
    TEAM_LABEL_COPY,
} from "./app-copy";
import { appPath } from "./app-paths";
import styles from "./Apps.module.css";
import { useCreateApp } from "./use-app-mutations";

export interface CreateAppDialogProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    readonly trigger: ReactNode;
}

export function CreateAppDialog({ orgId, orgName, trigger }: CreateAppDialogProps) {
    const [open, setOpen] = useState(false);

    return (
        <Dialog
            open={open}
            onOpenChange={setOpen}
            title={NEW_APP_COPY}
            description={NEW_APP_DESCRIPTION_COPY}
            trigger={trigger}
            wide
        >
            <CreateAppForm
                orgId={orgId}
                orgName={orgName}
                onCreated={() => {
                    setOpen(false);
                }}
            />
        </Dialog>
    );
}

const EMPTY: NewAppForm = { name: "", slug: "", cloudProvider: "AWS", region: "", teamId: "" };

/** Errors the backend reports against one field, and what the field then says. */
const FIELD_ERRORS = [
    [SLUG_TAKEN, "slug", SLUG_TAKEN_COPY],
    [INVALID_REGION, "region", INVALID_REGION_COPY],
    [TEAM_NOT_FOUND, "teamId", TEAM_GONE_COPY],
] as const;

interface CreateAppFormProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    readonly onCreated: () => void;
}

/** Mounted only while the dialog is open, so every new app starts from an empty form. */
function CreateAppForm({ orgId, orgName, onCreated }: CreateAppFormProps) {
    const router = useRouter();
    const toast = useToast();
    const create = useCreateApp(orgId);
    const teams = useTeamDirectory(orgId);
    const [failure, setFailure] = useState<unknown>();
    const [slugFollowsName, setSlugFollowsName] = useState(true);
    const form = useForm<NewAppForm, unknown, NewApp>({
        resolver: zodResolver(newAppSchema),
        defaultValues: EMPTY,
    });
    const { errors } = form.formState;
    const { setFocus } = form;
    const provider = useWatch({ control: form.control, name: "cloudProvider" });

    useEffect(() => {
        setFocus("name");
    }, [setFocus]);

    const changeProvider = (next: CloudProvider) => {
        form.setValue("cloudProvider", next);
        if (!isRegionOf(next, form.getValues("region"))) form.setValue("region", "");
    };

    const submit = form.handleSubmit((values) => {
        setFailure(undefined);
        create.mutate(values, {
            onSuccess: (app) => {
                toast.success(appCreatedCopy(app.name));
                onCreated();
                router.push(appPath(orgId, app.id));
            },
            onError: (error) => {
                const placed = FIELD_ERRORS.find(([code]) => error instanceof ApiError && error.is(code));
                if (placed !== undefined) {
                    const [, field, message] = placed;
                    form.setError(field, { type: "server", message }, { shouldFocus: true });
                    return;
                }
                const unplaced = applyServerErrors(form, error);
                const placedOnFields =
                    error instanceof ApiError && error.fieldErrors.length > 0 && unplaced.length === 0;
                if (!placedOnFields) setFailure(error);
            },
        });
    });

    return (
        <form onSubmit={(event) => void submit(event)} noValidate>
            <DialogBody>
                <Stack>
                    {failure !== undefined && (
                        <Callout tone="red" role="alert">
                            {messageFor(failure, createErrorCopy)}
                        </Callout>
                    )}
                    <FieldGrid>
                        <Field label={NAME_LABEL_COPY} error={errors.name?.message}>
                            <Input
                                placeholder="Payments API"
                                autoComplete="off"
                                {...form.register("name", {
                                    onChange: (event: { target: { value: string } }) => {
                                        if (slugFollowsName) form.setValue("slug", slugFromName(event.target.value));
                                    },
                                })}
                            />
                        </Field>
                        <Field label={SLUG_LABEL_COPY} hint={slugHintCopy(orgName)} error={errors.slug?.message}>
                            <Input
                                mono
                                placeholder="payments-api"
                                autoComplete="off"
                                autoCapitalize="none"
                                spellCheck={false}
                                {...form.register("slug", {
                                    onChange: (event: { target: { value: string } }) => {
                                        setSlugFollowsName(event.target.value === "");
                                    },
                                })}
                            />
                        </Field>
                    </FieldGrid>
                    <Field label={CLOUD_LABEL_COPY} error={errors.cloudProvider?.message}>
                        <Controller
                            control={form.control}
                            name="cloudProvider"
                            render={({ field }) => (
                                <ChoiceGroup
                                    ref={field.ref}
                                    value={field.value}
                                    onValueChange={(value) => {
                                        if (isCloudProvider(value)) changeProvider(value);
                                    }}
                                >
                                    {CLOUD_PROVIDERS.map((option) => (
                                        <Choice
                                            key={option}
                                            value={option}
                                            title={PROVIDER_NAME[option]}
                                            description={regionCountCopy(regionsFor(option).length)}
                                        />
                                    ))}
                                </ChoiceGroup>
                            )}
                        />
                    </Field>
                    <FieldGrid>
                        <Field label={REGION_LABEL_COPY} hint={REGION_HINT_COPY} error={errors.region?.message}>
                            <Select {...form.register("region")}>
                                <option value="" disabled>
                                    {REGION_PLACEHOLDER_COPY}
                                </option>
                                {regionsFor(provider).map((region) => (
                                    <option key={region.id} value={region.id}>
                                        {regionLabel(provider, region.id)}
                                    </option>
                                ))}
                            </Select>
                        </Field>
                        <Field
                            label={
                                <>
                                    {TEAM_LABEL_COPY} <span className={styles.optional}>{OPTIONAL_COPY}</span>
                                </>
                            }
                            error={errors.teamId?.message}
                        >
                            <Select {...form.register("teamId")}>
                                <option value="">{NO_TEAM_COPY}</option>
                                {teams.data?.map((team) => (
                                    <option key={team.id} value={team.id}>
                                        {team.name}
                                    </option>
                                ))}
                            </Select>
                        </Field>
                    </FieldGrid>
                    <Callout tone="amber">{FIXED_AT_CREATION_CALLOUT_COPY}</Callout>
                </Stack>
            </DialogBody>
            <DialogFooter>
                <DialogClose>
                    <Button variant="secondary" disabled={create.isPending}>
                        Cancel
                    </Button>
                </DialogClose>
                <Button type="submit" variant="primary" loading={create.isPending}>
                    {CREATE_APP_COPY}
                </Button>
            </DialogFooter>
        </form>
    );
}
