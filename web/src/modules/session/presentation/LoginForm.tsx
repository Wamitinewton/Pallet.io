"use client";

import { ApiError } from "@/shared/domain/errors";
import { messageFor } from "@/shared/presentation/errors";
import { applyServerErrors } from "@/shared/presentation/forms";
import { useSecondsUntil } from "@/shared/presentation/hooks";
import { useClock } from "@/shared/presentation/providers";
import {
    AuthForm,
    AuthHeader,
    Button,
    Callout,
    Divider,
    Field,
    Input,
    PasswordInput,
    Stack,
    Text,
    VisuallyHidden,
} from "@/shared/presentation/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { credentialsSchema, type Credentials } from "../domain/credentials";
import { classifySignInFailure, type SignInFailure } from "../domain/sign-in-failure";
import type { SignInReason } from "../domain/sign-in-path";
import { authPaths } from "./auth-paths";
import styles from "./LoginForm.module.css";
import {
    ACCOUNT_READY_COPY,
    EMAIL_CONFIRMED_COPY,
    openOrganizationCopy,
    PASSWORD_UPDATED_COPY,
    PASSWORD_UPDATED_DETAIL_COPY,
    SESSION_ENDED_COPY,
    signInErrorCopy,
} from "./sign-in-copy";
import { SignInFailureCallout } from "./SignInFailureCallout";
import { useSignIn } from "./use-sign-in";

interface FailedAttempt {
    readonly failure: SignInFailure;
    readonly email: string;
    readonly placedOnFields: boolean;
}

export interface LoginFormProps {
    readonly next?: string | undefined;
    readonly reason?: SignInReason | undefined;
    readonly verified?: boolean | undefined;
    readonly reset?: boolean | undefined;
    readonly joined?: string | undefined;
    readonly email?: string | undefined;
}

export function LoginForm({
    next,
    reason,
    verified = false,
    reset = false,
    joined,
    email: initialEmail = "",
}: LoginFormProps) {
    const clock = useClock();
    const { signIn, pending } = useSignIn(next);
    const [attempt, setAttempt] = useState<FailedAttempt>();
    const form = useForm<Credentials>({
        resolver: zodResolver(credentialsSchema),
        defaultValues: { email: initialEmail, password: "" },
    });
    const { errors } = form.formState;
    const email = useWatch({ control: form.control, name: "email" });

    const failure = attempt?.failure;
    const retryInSeconds = useSecondsUntil(failure?.kind === "rate-limited" ? failure.retryAt : undefined);
    const limited = failure?.kind === "rate-limited" && retryInSeconds > 0;

    const submit = form.handleSubmit((credentials) => {
        setAttempt(undefined);
        signIn(credentials, {
            onError: (error) => {
                const classified = classifySignInFailure(error, clock.now());
                const unplaced = classified.kind === "rejected" ? applyServerErrors(form, error) : [];
                const fieldErrorCount = error instanceof ApiError ? error.fieldErrors.length : 0;
                setAttempt({
                    failure: classified,
                    email: credentials.email,
                    placedOnFields: fieldErrorCount > 0 && unplaced.length === 0,
                });
                if (classified.kind === "invalid-credentials") {
                    form.resetField("password");
                    form.setFocus("password");
                }
            },
        });
    });

    return (
        <AuthForm onSubmit={(event) => void submit(event)} noValidate>
            <AuthHeader title="Sign in to Pallet" lede="Welcome back. Use the email you signed up with." />

            <VisuallyHidden role="status">
                {failure && messageFor(failure.error, signInErrorCopy, { reference: false })}
            </VisuallyHidden>

            {attempt === undefined && joined !== undefined && (
                <Callout tone="green">
                    <strong>{ACCOUNT_READY_COPY}</strong> {openOrganizationCopy(joined)}
                </Callout>
            )}
            {attempt === undefined && joined === undefined && verified && (
                <Callout tone="green">
                    <strong>{EMAIL_CONFIRMED_COPY}</strong> Sign in to open your organization.
                </Callout>
            )}
            {attempt === undefined && joined === undefined && !verified && reset && (
                <Callout tone="green">
                    <strong>{PASSWORD_UPDATED_COPY}</strong> {PASSWORD_UPDATED_DETAIL_COPY}
                </Callout>
            )}
            {attempt === undefined && joined === undefined && !verified && !reset && reason === "expired" && (
                <Callout tone="blue">{SESSION_ENDED_COPY}</Callout>
            )}

            {attempt && !attempt.placedOnFields && (
                <SignInFailureCallout
                    failure={attempt.failure}
                    email={attempt.email}
                    retryInSeconds={retryInSeconds}
                    onRetry={() => void submit()}
                    retrying={pending}
                />
            )}

            <Stack>
                <Field label="Email" error={errors.email?.message}>
                    <Input
                        type="email"
                        autoComplete="email"
                        autoCapitalize="none"
                        spellCheck={false}
                        {...form.register("email")}
                    />
                </Field>
                <Field
                    label="Password"
                    labelAside={<Link href={authPaths.forgotPassword(email)}>Forgot password?</Link>}
                    error={errors.password?.message}
                >
                    <PasswordInput autoComplete="current-password" {...form.register("password")} />
                </Field>
            </Stack>

            <Button type="submit" variant="primary" size="lg" block loading={pending} disabled={limited}>
                Sign in
            </Button>

            <Divider>New to Pallet?</Divider>
            <Button asChild variant="secondary" size="lg" block>
                <Link href={authPaths.signUp}>Create an organization</Link>
            </Button>
            <Text size="sm" tone="muted" className={styles.note}>
                Got an invite from your team? Open the link in the email instead.
            </Text>
        </AuthForm>
    );
}
