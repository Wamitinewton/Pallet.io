"use client";

import { authPaths } from "@/modules/session";
import type { RequestFailure } from "@/shared/domain/request-failure";
import { messageFor } from "@/shared/presentation/errors";
import { applyServerErrors } from "@/shared/presentation/forms";
import { useFocusOnMount, useReplaceAddress, useSecondsUntil } from "@/shared/presentation/hooks";
import { useClock } from "@/shared/presentation/providers";
import {
    AuthCard,
    AuthHeader,
    AuthIcon,
    Button,
    Field,
    PasswordInput,
    StrengthMeter,
    VisuallyHidden,
} from "@/shared/presentation/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useId, useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { strength } from "../domain/password-policy";
import { classifyResetFailure } from "../domain/reset-failure";
import { newPasswordSchema, type NewPassword } from "../domain/reset-password";
import { PASSWORD_HINT_COPY, RESET_LINK_EXPIRED_COPY, strengthLabel } from "./identity-copy";
import { RequestFailureCallout } from "./RequestFailureCallout";
import { useResetPassword } from "./use-password-recovery";

const EMPTY: NewPassword = { newPassword: "", confirmation: "" };

export interface ResetPasswordFormProps {
    readonly token?: string | undefined;
}

/** Holds the token from the emailed link in memory only and takes it out of the address bar on mount. */
export function ResetPasswordForm({ token: linkToken }: ResetPasswordFormProps) {
    const [token] = useState(linkToken);
    const [expired, setExpired] = useState(false);
    useReplaceAddress(authPaths.resetPassword);

    if (token === undefined || expired) return <LinkExpired announce={expired} />;
    return (
        <NewPasswordStep
            token={token}
            onExpired={() => {
                setExpired(true);
            }}
        />
    );
}

interface NewPasswordStepProps {
    readonly token: string;
    readonly onExpired: () => void;
}

function NewPasswordStep({ token, onExpired }: NewPasswordStepProps) {
    const clock = useClock();
    const { reset, pending } = useResetPassword();
    const [failure, setFailure] = useState<RequestFailure>();
    const form = useForm<NewPassword>({ resolver: zodResolver(newPasswordSchema), defaultValues: EMPTY });
    const { errors } = form.formState;
    const newPassword = useWatch({ control: form.control, name: "newPassword" });
    const passwordStrength = strength(newPassword);

    const retryInSeconds = useSecondsUntil(failure?.kind === "rate-limited" ? failure.retryAt : undefined);
    const limited = failure?.kind === "rate-limited" && retryInSeconds > 0;

    const submit = form.handleSubmit(({ newPassword: password }) => {
        setFailure(undefined);
        reset(
            { token, newPassword: password },
            {
                onError: (error) => {
                    const classified = classifyResetFailure(error, clock.now());
                    if (classified.kind === "expired") {
                        onExpired();
                        return;
                    }
                    const unplaced = classified.kind === "rejected" ? applyServerErrors(form, error) : [];
                    const placedOnFields =
                        classified.kind === "rejected" &&
                        classified.error.fieldErrors.length > 0 &&
                        unplaced.length === 0;
                    if (!placedOnFields) setFailure(classified);
                },
            },
        );
    });

    return (
        <AuthCard asChild>
            <form onSubmit={(event) => void submit(event)} noValidate>
                <AuthHeader title="Choose a new password" lede="Pick something you don't use on other sites." />

                <VisuallyHidden role="status">
                    {failure && messageFor(failure.error, {}, { reference: false })}
                </VisuallyHidden>

                {failure && (
                    <RequestFailureCallout
                        failure={failure}
                        retryInSeconds={retryInSeconds}
                        onRetry={() => void submit()}
                        retrying={pending}
                    />
                )}

                <Field label="New password" hint={PASSWORD_HINT_COPY} error={errors.newPassword?.message}>
                    <PasswordInput
                        autoComplete="new-password"
                        {...form.register("newPassword", { deps: "confirmation" })}
                    />
                    <StrengthMeter score={passwordStrength} aria-valuetext={strengthLabel(passwordStrength)} />
                </Field>
                <Field label="Type it again" error={errors.confirmation?.message}>
                    <PasswordInput autoComplete="new-password" {...form.register("confirmation")} />
                </Field>

                <Button type="submit" variant="primary" size="lg" block loading={pending} disabled={limited}>
                    Save new password
                </Button>
            </form>
        </AuthCard>
    );
}

function LinkExpired({ announce }: { readonly announce: boolean }) {
    const card = useFocusOnMount<HTMLDivElement>(announce);
    const headerId = useId();

    return (
        <AuthCard ref={card} tabIndex={-1} aria-labelledby={headerId}>
            <AuthIcon name="clock" tone="amber" />
            <AuthHeader id={headerId} title="This link has expired" lede={RESET_LINK_EXPIRED_COPY} />
            <Button asChild variant="primary" size="lg" block>
                <Link href={authPaths.forgotPassword()}>Send a new link</Link>
            </Button>
            <Button asChild variant="ghost" block>
                <Link href={authPaths.signIn()}>Back to sign in</Link>
            </Button>
        </AuthCard>
    );
}
