"use client";

import { ApiError } from "@/shared/domain/errors";
import type { OrgId } from "@/shared/domain/ids";
import { slugFromName } from "@/shared/domain/slug";
import { messageFor } from "@/shared/presentation/errors";
import { applyServerErrors } from "@/shared/presentation/forms";
import {
    Button,
    Callout,
    Dialog,
    DialogBody,
    DialogClose,
    DialogFooter,
    Field,
    Input,
    Stack,
    useToast,
} from "@/shared/presentation/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { useForm } from "react-hook-form";
import { newTeamSchema, type NewTeam, type NewTeamForm } from "../domain/team";
import { SLUG_TAKEN } from "../domain/team-errors";
import {
    CREATE_TEAM_COPY,
    createErrorCopy,
    NAME_LABEL_COPY,
    NEW_TEAM_COPY,
    NEW_TEAM_DESCRIPTION_COPY,
    SLUG_HINT_COPY,
    SLUG_LABEL_COPY,
    SLUG_TAKEN_COPY,
    teamCreatedCopy,
} from "./team-copy";
import { teamPath } from "./team-paths";
import { useCreateTeam } from "./use-team-mutations";

export interface NewTeamDialogProps {
    readonly orgId: OrgId;
    readonly trigger: ReactNode;
}

export function NewTeamDialog({ orgId, trigger }: NewTeamDialogProps) {
    const [open, setOpen] = useState(false);

    return (
        <Dialog
            open={open}
            onOpenChange={setOpen}
            title={NEW_TEAM_COPY}
            description={NEW_TEAM_DESCRIPTION_COPY}
            trigger={trigger}
        >
            <NewTeamFormBody
                orgId={orgId}
                onCreated={() => {
                    setOpen(false);
                }}
            />
        </Dialog>
    );
}

const EMPTY: NewTeamForm = { name: "", slug: "" };

/** Mounted only while the dialog is open, so every new team starts from an empty form. */
function NewTeamFormBody({ orgId, onCreated }: Readonly<{ orgId: OrgId; onCreated: () => void }>) {
    const router = useRouter();
    const toast = useToast();
    const create = useCreateTeam(orgId);
    const [failure, setFailure] = useState<unknown>();
    const [slugFollowsName, setSlugFollowsName] = useState(true);
    const form = useForm<NewTeamForm, unknown, NewTeam>({
        resolver: zodResolver(newTeamSchema),
        defaultValues: EMPTY,
    });
    const { errors } = form.formState;
    const { setFocus } = form;

    useEffect(() => {
        setFocus("name");
    }, [setFocus]);

    const submit = form.handleSubmit((values) => {
        setFailure(undefined);
        create.mutate(values, {
            onSuccess: (team) => {
                toast.success(teamCreatedCopy(team.name));
                onCreated();
                router.push(teamPath(orgId, team.id));
            },
            onError: (error) => {
                if (error instanceof ApiError && error.is(SLUG_TAKEN)) {
                    form.setError("slug", { type: "server", message: SLUG_TAKEN_COPY }, { shouldFocus: true });
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
                    <Field label={NAME_LABEL_COPY} error={errors.name?.message}>
                        <Input
                            placeholder="Data"
                            autoComplete="off"
                            {...form.register("name", {
                                onChange: (event: { target: { value: string } }) => {
                                    if (slugFollowsName) form.setValue("slug", slugFromName(event.target.value));
                                },
                            })}
                        />
                    </Field>
                    <Field label={SLUG_LABEL_COPY} hint={SLUG_HINT_COPY} error={errors.slug?.message}>
                        <Input
                            mono
                            placeholder="data"
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
                </Stack>
            </DialogBody>
            <DialogFooter>
                <DialogClose>
                    <Button variant="secondary" disabled={create.isPending}>
                        Cancel
                    </Button>
                </DialogClose>
                <Button type="submit" variant="primary" loading={create.isPending}>
                    {CREATE_TEAM_COPY}
                </Button>
            </DialogFooter>
        </form>
    );
}
