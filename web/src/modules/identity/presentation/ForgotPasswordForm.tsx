"use client";

import { authPaths } from "@/modules/session";
import { emailSchema } from "@/shared/domain/email";
import { classifyRequestFailure, type RequestFailure } from "@/shared/domain/request-failure";
import { messageFor } from "@/shared/presentation/errors";
import { applyServerErrors } from "@/shared/presentation/forms";
import { useFocusOnMount, useSecondsUntil } from "@/shared/presentation/hooks";
import { useClock } from "@/shared/presentation/providers";
import {
    AuthCard,
    AuthHeader,
    AuthIcon,
    Button,
    Callout,
    Field,
    Icon,
    Input,
    Row,
    VisuallyHidden,
} from "@/shared/presentation/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useEffect, useId, useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { z } from "zod";
import { RESET_LINK_HELP_COPY } from "./identity-copy";
import styles from "./PasswordRecovery.module.css";
import { RequestFailureCallout } from "./RequestFailureCallout";
import { useRequestPasswordReset } from "./use-password-recovery";

const requestSchema = z.object({ email: emailSchema });

type ResetRequest = z.output<typeof requestSchema>;

export interface ForgotPasswordFormProps {
    readonly email?: string | undefined;
}

export function ForgotPasswordForm({ email = "" }: ForgotPasswordFormProps) {
    const [draft, setDraft] = useState({ email, returning: false });
    const [sentTo, setSentTo] = useState<string>();

    if (sentTo !== undefined) {
        return (
            <LinkSent
                email={sentTo}
                onUseDifferentEmail={() => {
                    setDraft({ email: sentTo, returning: true });
                    setSentTo(undefined);
                }}
            />
        );
    }
    return <RequestStep defaultEmail={draft.email} focusEmail={draft.returning} onSent={setSentTo} />;
}

interface RequestStepProps {
    readonly defaultEmail: string;
    readonly focusEmail: boolean;
    readonly onSent: (email: string) => void;
}

function RequestStep({ defaultEmail, focusEmail, onSent }: RequestStepProps) {
    const clock = useClock();
    const { request, pending } = useRequestPasswordReset();
    const [failure, setFailure] = useState<RequestFailure>();
    const form = useForm<ResetRequest>({
        resolver: zodResolver(requestSchema),
        defaultValues: { email: defaultEmail },
    });
    const email = useWatch({ control: form.control, name: "email" });

    const retryInSeconds = useSecondsUntil(failure?.kind === "rate-limited" ? failure.retryAt : undefined);
    const limited = failure?.kind === "rate-limited" && retryInSeconds > 0;

    useEffect(() => {
        if (focusEmail) form.setFocus("email", { shouldSelect: true });
    }, [focusEmail, form]);

    const submit = form.handleSubmit(({ email: address }) => {
        setFailure(undefined);
        request(address, {
            onSuccess: () => {
                onSent(address);
            },
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
        <AuthCard asChild>
            <form onSubmit={(event) => void submit(event)} noValidate>
                <AuthHeader
                    title="Forgot your password?"
                    lede="Enter the email you use for Pallet and we'll send you a link to choose a new one."
                />

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

                <Field label="Email" error={form.formState.errors.email?.message}>
                    <Input
                        type="email"
                        autoComplete="email"
                        autoCapitalize="none"
                        spellCheck={false}
                        {...form.register("email")}
                    />
                </Field>

                <Button type="submit" variant="primary" size="lg" block loading={pending} disabled={limited}>
                    Send reset link
                </Button>
                <Button asChild variant="ghost" block>
                    <Link href={authPaths.signIn({ email })}>
                        <Icon name="back" />
                        Back to sign in
                    </Link>
                </Button>
            </form>
        </AuthCard>
    );
}

interface LinkSentProps {
    readonly email: string;
    readonly onUseDifferentEmail: () => void;
}

/** Drawn the same for every `202`: the page never says whether an account exists. */
function LinkSent({ email, onUseDifferentEmail }: LinkSentProps) {
    const card = useFocusOnMount<HTMLDivElement>();
    const headerId = useId();

    return (
        <AuthCard ref={card} tabIndex={-1} aria-labelledby={headerId}>
            <AuthIcon name="send" />
            <AuthHeader
                id={headerId}
                title="Check your email"
                lede={
                    <>
                        If an account exists for <strong className={styles.email}>{email}</strong>, a reset link is on
                        its way. It works once, for one hour.
                    </>
                }
            />
            <Callout>{RESET_LINK_HELP_COPY}</Callout>
            <Row>
                <Button variant="secondary" onClick={onUseDifferentEmail}>
                    Use a different email
                </Button>
                <Button asChild variant="ghost">
                    <Link href={authPaths.signIn({ email })}>Back to sign in</Link>
                </Button>
            </Row>
        </AuthCard>
    );
}
