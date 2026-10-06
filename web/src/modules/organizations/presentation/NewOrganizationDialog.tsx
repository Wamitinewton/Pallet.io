"use client";

import { ApiError } from "@/shared/domain/errors";
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
    InputGroup,
    Stack,
    useToast,
} from "@/shared/presentation/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import type { Route } from "next";
import { useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { useForm } from "react-hook-form";
import { organizationPath } from "../domain/org-paths";
import {
    newOrganizationSchema,
    SLUG_TAKEN,
    type NewOrganization,
    type NewOrganizationForm,
} from "../domain/organization";
import {
    NEW_ORGANIZATION_DESCRIPTION_COPY,
    NEW_SLUG_HINT_COPY,
    ORGANIZATION_CREATED_COPY,
    SLUG_HOST,
    SLUG_TAKEN_COPY,
} from "./organization-copy";
import { useCreateOrganization } from "./use-organization-mutations";

export interface NewOrganizationDialogProps {
    readonly open?: boolean;
    readonly onOpenChange?: (open: boolean) => void;
    readonly trigger?: ReactNode;
}

export function NewOrganizationDialog({ open, onOpenChange, trigger }: NewOrganizationDialogProps) {
    const [uncontrolledOpen, setUncontrolledOpen] = useState(false);
    const isOpen = open ?? uncontrolledOpen;
    const setOpen = onOpenChange ?? setUncontrolledOpen;

    return (
        <Dialog
            open={isOpen}
            onOpenChange={setOpen}
            title="New organization"
            description={NEW_ORGANIZATION_DESCRIPTION_COPY}
            {...(trigger !== undefined && { trigger })}
        >
            <NewOrganizationForm
                onCreated={() => {
                    setOpen(false);
                }}
            />
        </Dialog>
    );
}

const EMPTY: NewOrganizationForm = { name: "", slug: "" };

/** Mounted only while the dialog is open, so every new organization starts from an empty form. */
function NewOrganizationForm({ onCreated }: Readonly<{ onCreated: () => void }>) {
    const router = useRouter();
    const toast = useToast();
    const create = useCreateOrganization();
    const [failure, setFailure] = useState<unknown>();
    const [slugFollowsName, setSlugFollowsName] = useState(true);
    const form = useForm<NewOrganizationForm, unknown, NewOrganization>({
        resolver: zodResolver(newOrganizationSchema),
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
            onSuccess: (organization) => {
                toast.success(ORGANIZATION_CREATED_COPY);
                onCreated();
                router.push(organizationPath(organization.orgId) as Route);
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
                            {messageFor(failure)}
                        </Callout>
                    )}
                    <Field label="Name" error={errors.name?.message}>
                        <Input
                            placeholder="Savanna Pay"
                            autoComplete="off"
                            {...form.register("name", {
                                onChange: (event: { target: { value: string } }) => {
                                    if (slugFollowsName) form.setValue("slug", slugFromName(event.target.value));
                                },
                            })}
                        />
                    </Field>
                    <Field label="URL" hint={NEW_SLUG_HINT_COPY} error={errors.slug?.message}>
                        <InputGroup addon={SLUG_HOST}>
                            <Input
                                mono
                                placeholder="savanna-pay"
                                autoComplete="off"
                                autoCapitalize="none"
                                spellCheck={false}
                                {...form.register("slug", {
                                    onChange: (event: { target: { value: string } }) => {
                                        setSlugFollowsName(event.target.value === "");
                                    },
                                })}
                            />
                        </InputGroup>
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
                    Create organization
                </Button>
            </DialogFooter>
        </form>
    );
}
