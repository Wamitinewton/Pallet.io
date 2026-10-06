"use client";

import { ApiError } from "@/shared/domain/errors";
import type { OrgId } from "@/shared/domain/ids";
import type { Role } from "@/shared/domain/role";
import { messageFor } from "@/shared/presentation/errors";
import { applyServerErrors } from "@/shared/presentation/forms";
import { ROLE_DESCRIPTION, ROLE_LABEL } from "@/shared/presentation/roles";
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
    Icon,
    Input,
    Stack,
    useToast,
} from "@/shared/presentation/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import { useEffect, useState } from "react";
import { Controller, useForm, useWatch } from "react-hook-form";
import {
    createInviteSchema,
    defaultInviteRole,
    invitableRolesFor,
    type CreateInviteForm,
    type NewInvite,
} from "../domain/create-invite";
import { INVITE_ALREADY_PENDING } from "../domain/invite-errors";
import {
    INVITE_DIALOG_DESCRIPTION_COPY,
    invitedCopy,
    inviteDialogTitle,
    inviteEmailErrorCopy,
    inviteErrorCopy,
    NOTHING_PENDING_COPY,
    RESEND_PENDING_COPY,
    resendErrorCopy,
    resentCopy,
    SEND_INVITE_COPY,
} from "./invite-copy";
import styles from "./Invites.module.css";
import { useCreateInvite, useResendPendingInvite } from "./use-invite-mutations";

export interface InviteDraft {
    readonly email: string;
    readonly role: Role;
}

export interface InviteDialogProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    /** Decides which roles are offered. */
    readonly callerRole: Role;
    readonly open: boolean;
    /** Fills the form in, as when inviting someone again. */
    readonly draft: InviteDraft | undefined;
    readonly onOpenChange: (open: boolean) => void;
}

export function InviteDialog({ orgId, orgName, callerRole, open, draft, onOpenChange }: InviteDialogProps) {
    return (
        <Dialog
            open={open}
            onOpenChange={onOpenChange}
            title={inviteDialogTitle(orgName)}
            description={INVITE_DIALOG_DESCRIPTION_COPY}
        >
            <InviteForm
                orgId={orgId}
                callerRole={callerRole}
                draft={draft}
                onDone={() => {
                    onOpenChange(false);
                }}
            />
        </Dialog>
    );
}

interface InviteFormProps {
    readonly orgId: OrgId;
    readonly callerRole: Role;
    readonly draft: InviteDraft | undefined;
    readonly onDone: () => void;
}

function initialValues(draft: InviteDraft | undefined, callerRole: Role): CreateInviteForm {
    const roles: readonly Role[] = invitableRolesFor(callerRole);
    const role = draft !== undefined && roles.includes(draft.role) ? draft.role : defaultInviteRole(callerRole);
    return { email: draft?.email ?? "", role: role ?? "" };
}

/** Mounted only while the dialog is open, so every invite starts from a fresh form. */
function InviteForm({ orgId, callerRole, draft, onDone }: InviteFormProps) {
    const toast = useToast();
    const create = useCreateInvite(orgId);
    const resendPending = useResendPendingInvite(orgId);
    const [failure, setFailure] = useState<string>();
    const [alreadyPendingFor, setAlreadyPendingFor] = useState<string>();
    const form = useForm<CreateInviteForm, unknown, NewInvite>({
        resolver: zodResolver(createInviteSchema(callerRole)),
        defaultValues: initialValues(draft, callerRole),
    });
    const { errors } = form.formState;
    const { setFocus } = form;
    const email = useWatch({ control: form.control, name: "email" });
    const offerResend = alreadyPendingFor !== undefined && email.trim().toLowerCase() === alreadyPendingFor;
    const busy = create.isPending || resendPending.isPending;

    useEffect(() => {
        setFocus("email");
    }, [setFocus]);

    const submit = form.handleSubmit((invite) => {
        setFailure(undefined);
        setAlreadyPendingFor(undefined);
        create.mutate(invite, {
            onSuccess: (created) => {
                toast.success(invitedCopy(created.email));
                onDone();
            },
            onError: (error) => {
                const onEmail = error instanceof ApiError ? inviteEmailErrorCopy[error.code] : undefined;
                if (onEmail !== undefined) {
                    form.setError("email", { type: "server", message: onEmail }, { shouldFocus: true });
                    if (error instanceof ApiError && error.is(INVITE_ALREADY_PENDING)) {
                        setAlreadyPendingFor(invite.email);
                    }
                    return;
                }
                const unplaced = applyServerErrors(form, error);
                const placedOnFields =
                    error instanceof ApiError && error.fieldErrors.length > 0 && unplaced.length === 0;
                if (!placedOnFields) setFailure(messageFor(error, inviteErrorCopy));
            },
        });
    });

    const resendExisting = () => {
        if (alreadyPendingFor === undefined || busy) return;
        const address = alreadyPendingFor;
        setFailure(undefined);
        resendPending.mutate(address, {
            onSuccess: (invite) => {
                if (invite !== undefined) {
                    toast.success(resentCopy(invite.email));
                    onDone();
                    return;
                }
                setAlreadyPendingFor(undefined);
                form.clearErrors("email");
                setFailure(NOTHING_PENDING_COPY);
            },
            onError: (error) => {
                setFailure(messageFor(error, resendErrorCopy(address)));
            },
        });
    };

    return (
        <form onSubmit={(event) => void submit(event)} noValidate>
            <DialogBody>
                <Stack>
                    {failure !== undefined && (
                        <Callout tone="red" role="alert">
                            {failure}
                        </Callout>
                    )}
                    <div>
                        <Field label="Email address" error={errors.email?.message}>
                            <Input
                                type="email"
                                placeholder="name@company.com"
                                autoComplete="off"
                                autoCapitalize="none"
                                spellCheck={false}
                                {...form.register("email")}
                            />
                        </Field>
                        {offerResend && (
                            <Button
                                variant="secondary"
                                size="sm"
                                className={styles.resendPending}
                                loading={resendPending.isPending}
                                onClick={resendExisting}
                            >
                                <Icon name="send" />
                                {RESEND_PENDING_COPY}
                            </Button>
                        )}
                    </div>
                    <Field label="Role" error={errors.role?.message}>
                        <Controller
                            control={form.control}
                            name="role"
                            render={({ field }) => (
                                <ChoiceGroup
                                    ref={field.ref}
                                    value={field.value}
                                    onValueChange={field.onChange}
                                    className={styles.roleOptions}
                                >
                                    {invitableRolesFor(callerRole).map((role) => (
                                        <Choice
                                            key={role}
                                            value={role}
                                            title={ROLE_LABEL[role]}
                                            description={ROLE_DESCRIPTION[role]}
                                        />
                                    ))}
                                </ChoiceGroup>
                            )}
                        />
                    </Field>
                </Stack>
            </DialogBody>
            <DialogFooter>
                <DialogClose>
                    <Button variant="secondary" disabled={busy}>
                        Cancel
                    </Button>
                </DialogClose>
                <Button type="submit" variant="primary" loading={create.isPending} disabled={resendPending.isPending}>
                    <Icon name="send" />
                    {SEND_INVITE_COPY}
                </Button>
            </DialogFooter>
        </form>
    );
}
