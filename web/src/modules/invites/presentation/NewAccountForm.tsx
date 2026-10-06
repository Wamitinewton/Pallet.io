"use client";

import { authPaths } from "@/modules/session";
import { strength } from "@/shared/domain/password-policy";
import { classifyRequestFailure, type RequestFailure } from "@/shared/domain/request-failure";
import { messageFor, RequestFailureCallout } from "@/shared/presentation/errors";
import { applyServerErrors, PASSWORD_HINT_COPY, strengthLabel } from "@/shared/presentation/forms";
import { useSecondsUntil } from "@/shared/presentation/hooks";
import { useClock } from "@/shared/presentation/providers";
import { Button, Field, PasswordInput, StrengthMeter, Text, VisuallyHidden } from "@/shared/presentation/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useId, useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { newAccountSchema, type NewAccount } from "../domain/accept-invite";
import {
    ACCEPT_AND_JOIN_COPY,
    HAVE_AN_ACCOUNT_COPY,
    PASSWORD_PLACEHOLDER_COPY,
    SET_PASSWORD_LEDE_COPY,
    SET_PASSWORD_TITLE_COPY,
    SIGN_IN_TO_ACCEPT_COPY,
} from "./acceptance-copy";
import { invitePath } from "./invite-paths";
import styles from "./InviteAccept.module.css";
import { useAcceptWithNewAccount, type UnacceptedOutcome } from "./use-invite-acceptance";

export interface NewAccountFormProps {
    readonly token: string;
    readonly orgName: string;
    readonly onOutcome: (outcome: UnacceptedOutcome) => void;
}

export function NewAccountForm({ token, orgName, onOutcome }: NewAccountFormProps) {
    const clock = useClock();
    const { accept, pending } = useAcceptWithNewAccount(token, orgName);
    const titleId = useId();
    const [failure, setFailure] = useState<RequestFailure>();
    const form = useForm<NewAccount>({ resolver: zodResolver(newAccountSchema), defaultValues: { password: "" } });
    const { errors } = form.formState;
    const password = useWatch({ control: form.control, name: "password" });
    const passwordStrength = strength(password);

    const retryInSeconds = useSecondsUntil(failure?.kind === "rate-limited" ? failure.retryAt : undefined);
    const limited = failure?.kind === "rate-limited" && retryInSeconds > 0;

    const submit = form.handleSubmit((account) => {
        setFailure(undefined);
        accept(account.password, {
            onOutcome,
            onError: (error) => {
                const classified = classifyRequestFailure(error, clock.now());
                const unplaced = classified.kind === "rejected" ? applyServerErrors(form, error) : [];
                const placedOnFields =
                    classified.kind === "rejected" && classified.error.fieldErrors.length > 0 && unplaced.length === 0;
                if (!placedOnFields) setFailure(classified);
            },
        });
    });

    return (
        <form className={styles.form} onSubmit={(event) => void submit(event)} noValidate aria-labelledby={titleId}>
            <hr className={styles.separator} />
            <div>
                <h2 id={titleId}>{SET_PASSWORD_TITLE_COPY}</h2>
                <Text asChild size="sm" tone="muted">
                    <p className={styles.lede}>{SET_PASSWORD_LEDE_COPY}</p>
                </Text>
            </div>

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

            <Field label="Password" hint={PASSWORD_HINT_COPY} error={errors.password?.message}>
                <PasswordInput
                    autoComplete="new-password"
                    placeholder={PASSWORD_PLACEHOLDER_COPY}
                    {...form.register("password")}
                />
                <StrengthMeter score={passwordStrength} aria-valuetext={strengthLabel(passwordStrength)} />
            </Field>

            <Button type="submit" variant="primary" size="lg" block loading={pending} disabled={limited}>
                {ACCEPT_AND_JOIN_COPY}
            </Button>

            <Text asChild size="sm" tone="muted">
                <p>
                    {HAVE_AN_ACCOUNT_COPY}{" "}
                    <Link href={authPaths.signIn({ next: invitePath(token) })}>{SIGN_IN_TO_ACCEPT_COPY}</Link>.
                </p>
            </Text>
        </form>
    );
}
