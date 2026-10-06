"use client";

import { authPaths } from "@/modules/session";
import { strength } from "@/shared/domain/password-policy";
import { messageFor, RequestFailureCallout } from "@/shared/presentation/errors";
import { applyServerErrors, PASSWORD_HINT_COPY, strengthLabel, useIdempotencyKey } from "@/shared/presentation/forms";
import { useSecondsUntil } from "@/shared/presentation/hooks";
import { useClock } from "@/shared/presentation/providers";
import {
    AuthForm,
    AuthHeader,
    Button,
    Callout,
    Field,
    FieldGrid,
    Input,
    PasswordInput,
    Stack,
    StrengthMeter,
    Text,
    VisuallyHidden,
} from "@/shared/presentation/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import { useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useEffect, useState, type ChangeEvent } from "react";
import { useForm, useWatch } from "react-hook-form";
import { signupSchema, slugFromName, type SignupDetails, type SlugAvailability } from "../domain/signup";
import { classifySignupFailure, IDEMPOTENCY_KEY_REUSE, type SignupFailure } from "../domain/signup-failure";
import { EMAIL_TAKEN_COPY, SLUG_OR_EMAIL_TAKEN_COPY } from "./identity-copy";
import { identityKeys } from "./queries";
import styles from "./SignupForm.module.css";
import { SlugField } from "./SlugField";
import { useSignUp } from "./use-sign-up";

const EMAIL_TAKEN = "taken";

const EMPTY: SignupDetails = { organizationName: "", slug: "", displayName: "", email: "", password: "" };

type CalloutFailure = Exclude<SignupFailure, { kind: "taken"; field: "slug" | "email" }>;

function calloutMessage(failure: CalloutFailure): string {
    return failure.kind === "taken" ? SLUG_OR_EMAIL_TAKEN_COPY : messageFor(failure.error, {}, { reference: false });
}

export function SignupForm() {
    const clock = useClock();
    const queryClient = useQueryClient();
    const { signUp, pending } = useSignUp();
    const idempotencyKey = useIdempotencyKey();
    const [slugFollowsName, setSlugFollowsName] = useState(true);
    const [failure, setFailure] = useState<CalloutFailure>();
    const form = useForm<SignupDetails>({ resolver: zodResolver(signupSchema), defaultValues: EMPTY });
    const { errors, isSubmitted } = form.formState;
    const [organizationName, slug, email, password] = useWatch({
        control: form.control,
        name: ["organizationName", "slug", "email", "password"],
    });
    const passwordStrength = strength(password);

    const retryInSeconds = useSecondsUntil(failure?.kind === "rate-limited" ? failure.retryAt : undefined);
    const limited = failure?.kind === "rate-limited" && retryInSeconds > 0;

    useEffect(
        () => form.subscribe({ formState: { values: true }, callback: idempotencyKey.renew }),
        [form, idempotencyKey],
    );

    const setSlug = (value: string) => {
        form.setValue("slug", value, { shouldDirty: true, shouldValidate: isSubmitted });
    };

    const fail = (details: SignupDetails, error: unknown) => {
        const classified = classifySignupFailure(error, clock.now());
        switch (classified.kind) {
            case "taken":
                if (classified.field === "slug") {
                    queryClient.setQueryData<SlugAvailability>(identityKeys.slugAvailability(details.slug), {
                        slug: details.slug,
                        available: false,
                    });
                    form.setFocus("slug");
                } else if (classified.field === "email") {
                    form.setError("email", { type: EMAIL_TAKEN, message: EMAIL_TAKEN_COPY }, { shouldFocus: true });
                } else {
                    setFailure({ kind: "taken", field: undefined });
                }
                return;
            case "rejected": {
                if (classified.error.is(IDEMPOTENCY_KEY_REUSE)) idempotencyKey.renew();
                const unplaced = applyServerErrors(form, error);
                const allPlaced = classified.error.fieldErrors.length > 0 && unplaced.length === 0;
                if (!allPlaced) setFailure(classified);
                return;
            }
            case "rate-limited":
            case "unavailable":
                setFailure(classified);
                return;
        }
    };

    const submit = form.handleSubmit((details) => {
        setFailure(undefined);
        signUp(details, idempotencyKey.current(), {
            onError: (error) => {
                fail(details, error);
            },
        });
    });

    const organizationNameField = form.register("organizationName", {
        onChange: (event: ChangeEvent<HTMLInputElement>) => {
            if (slugFollowsName) setSlug(slugFromName(event.target.value));
        },
    });
    const slugField = form.register("slug", {
        onChange: () => {
            setSlugFollowsName(false);
        },
    });

    return (
        <AuthForm onSubmit={(event) => void submit(event)} noValidate>
            <AuthHeader
                title="Create your organization"
                lede="Your organization holds your apps and your team. You'll be its owner, and you can invite everyone else once you're in."
            />

            <VisuallyHidden role="status">{failure && calloutMessage(failure)}</VisuallyHidden>

            {failure?.kind === "taken" && <Callout tone="red">{SLUG_OR_EMAIL_TAKEN_COPY}</Callout>}
            {failure && failure.kind !== "taken" && (
                <RequestFailureCallout
                    failure={failure}
                    retryInSeconds={retryInSeconds}
                    onRetry={() => void submit()}
                    retrying={pending}
                />
            )}

            <Stack>
                <Field label="Organization name" error={errors.organizationName?.message}>
                    <Input autoComplete="organization" {...organizationNameField} />
                </Field>

                <SlugField
                    slug={slug}
                    organizationName={organizationName}
                    error={errors.slug?.message}
                    onSuggestion={(suggestion) => {
                        setSlugFollowsName(false);
                        setSlug(suggestion);
                        form.setFocus("slug");
                    }}
                    {...slugField}
                />

                <FieldGrid>
                    <Field label="Your name" error={errors.displayName?.message}>
                        <Input autoComplete="name" {...form.register("displayName")} />
                    </Field>
                    <Field
                        label="Work email"
                        error={
                            errors.email?.type === EMAIL_TAKEN ? (
                                <>
                                    {errors.email.message}{" "}
                                    <Link href={authPaths.signIn({ email })}>Sign in instead</Link>.
                                </>
                            ) : (
                                errors.email?.message
                            )
                        }
                    >
                        <Input
                            type="email"
                            autoComplete="email"
                            autoCapitalize="none"
                            spellCheck={false}
                            {...form.register("email")}
                        />
                    </Field>
                </FieldGrid>

                <Field label="Password" hint={PASSWORD_HINT_COPY} error={errors.password?.message}>
                    <PasswordInput autoComplete="new-password" {...form.register("password")} />
                    <StrengthMeter score={passwordStrength} aria-valuetext={strengthLabel(passwordStrength)} />
                </Field>
            </Stack>

            <Button type="submit" variant="primary" size="lg" block loading={pending} disabled={limited}>
                Create organization
            </Button>

            <Text size="sm" tone="muted" className={styles.note}>
                Already on Pallet? <Link href={authPaths.signIn()}>Sign in</Link>.
            </Text>
        </AuthForm>
    );
}
