"use client";

import { authPaths } from "@/modules/session";
import { strength } from "@/shared/domain/password-policy";
import type { RequestFailure } from "@/shared/domain/request-failure";
import { messageFor, RequestFailureCallout } from "@/shared/presentation/errors";
import { applyServerErrors, strengthLabel } from "@/shared/presentation/forms";
import { useSecondsUntil } from "@/shared/presentation/hooks";
import { useClock } from "@/shared/presentation/providers";
import {
    Button,
    Field,
    FieldGrid,
    Panel,
    PanelBody,
    PanelFoot,
    PanelHead,
    PasswordInput,
    Stack,
    StrengthMeter,
    useToast,
    VisuallyHidden,
} from "@/shared/presentation/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import {
    changePasswordFormSchema,
    classifyPasswordChangeFailure,
    toPasswordChange,
    type ChangePasswordForm,
} from "../domain/change-password";
import {
    NEW_PASSWORD_HINT_COPY,
    PASSWORD_CHANGED_COPY,
    PASSWORD_DESCRIPTION_COPY,
    WRONG_CURRENT_PASSWORD_COPY,
} from "./account-copy";
import { useChangePassword } from "./use-account";

const EMPTY: ChangePasswordForm = { currentPassword: "", newPassword: "", confirmation: "" };

export interface PasswordPanelProps {
    readonly email: string;
}

export function PasswordPanel({ email }: PasswordPanelProps) {
    const clock = useClock();
    const toast = useToast();
    const change = useChangePassword();
    const [failure, setFailure] = useState<RequestFailure>();
    const form = useForm<ChangePasswordForm>({ resolver: zodResolver(changePasswordFormSchema), defaultValues: EMPTY });
    const { errors } = form.formState;
    const newPassword = useWatch({ control: form.control, name: "newPassword" });
    const passwordStrength = strength(newPassword);

    const retryInSeconds = useSecondsUntil(failure?.kind === "rate-limited" ? failure.retryAt : undefined);
    const limited = failure?.kind === "rate-limited" && retryInSeconds > 0;

    const submit = form.handleSubmit((values) => {
        setFailure(undefined);
        change.mutate(toPasswordChange(values), {
            onSuccess: () => {
                form.reset(EMPTY);
                toast.success(PASSWORD_CHANGED_COPY);
            },
            onError: (error) => {
                const classified = classifyPasswordChangeFailure(error, clock.now());
                if (classified.kind === "wrong-current-password") {
                    form.setError(
                        "currentPassword",
                        { type: "server", message: WRONG_CURRENT_PASSWORD_COPY },
                        { shouldFocus: true },
                    );
                    return;
                }
                const unplaced = classified.kind === "rejected" ? applyServerErrors(form, error) : [];
                const placedOnFields =
                    classified.kind === "rejected" && classified.error.fieldErrors.length > 0 && unplaced.length === 0;
                if (!placedOnFields) setFailure(classified);
            },
        });
    });

    return (
        <Panel>
            <form onSubmit={(event) => void submit(event)} noValidate>
                <PanelHead title="Change password" description={PASSWORD_DESCRIPTION_COPY} />
                <PanelBody>
                    <Stack>
                        <VisuallyHidden role="status">
                            {failure && messageFor(failure.error, {}, { reference: false })}
                        </VisuallyHidden>
                        {failure && (
                            <RequestFailureCallout
                                failure={failure}
                                retryInSeconds={retryInSeconds}
                                onRetry={() => void submit()}
                                retrying={change.isPending}
                            />
                        )}
                        <Field label="Current password" error={errors.currentPassword?.message}>
                            <PasswordInput autoComplete="current-password" {...form.register("currentPassword")} />
                        </Field>
                        <FieldGrid>
                            <Field
                                label="New password"
                                hint={NEW_PASSWORD_HINT_COPY}
                                error={errors.newPassword?.message}
                            >
                                <PasswordInput
                                    autoComplete="new-password"
                                    {...form.register("newPassword", { deps: "confirmation" })}
                                />
                                <StrengthMeter
                                    score={passwordStrength}
                                    aria-valuetext={strengthLabel(passwordStrength)}
                                />
                            </Field>
                            <Field label="Type it again" error={errors.confirmation?.message}>
                                <PasswordInput autoComplete="new-password" {...form.register("confirmation")} />
                            </Field>
                        </FieldGrid>
                    </Stack>
                </PanelBody>
                <PanelFoot>
                    <Link href={authPaths.forgotPassword(email)}>Forgot your current password?</Link>
                    <Button type="submit" variant="primary" size="sm" loading={change.isPending} disabled={limited}>
                        Update password
                    </Button>
                </PanelFoot>
            </form>
        </Panel>
    );
}
