"use client";

import { messageFor, tooManyAttemptsCopy } from "@/shared/presentation/errors";
import { Button, Callout, Icon, Row, Stack } from "@/shared/presentation/ui";
import Link from "next/link";
import type { SignInFailure } from "../domain/sign-in-failure";
import { authPaths } from "./auth-paths";
import { signInErrorCopy } from "./sign-in-copy";

export interface SignInFailureCalloutProps {
    readonly failure: SignInFailure;
    readonly email: string;
    readonly retryInSeconds: number;
    readonly onRetry: () => void;
    readonly retrying: boolean;
}

export function SignInFailureCallout({ failure, email, retryInSeconds, onRetry, retrying }: SignInFailureCalloutProps) {
    switch (failure.kind) {
        case "invalid-credentials":
            return (
                <Callout tone="red">
                    <strong>{messageFor(failure.error, signInErrorCopy)}</strong> Check for typos, or{" "}
                    <Link href={authPaths.forgotPassword(email)}>reset your password</Link>.
                </Callout>
            );
        case "email-not-verified":
            return (
                <Callout tone="amber" icon="mail">
                    <Stack gap="sm">
                        <span>
                            <strong>{messageFor(failure.error, signInErrorCopy)}</strong> We sent a code to {email} when
                            you signed up.
                        </span>
                        <Row>
                            <Button asChild variant="secondary" size="sm">
                                <Link href={authPaths.verifyEmail(email)}>Enter code</Link>
                            </Button>
                        </Row>
                    </Stack>
                </Callout>
            );
        case "rate-limited":
            if (retryInSeconds === 0) return null;
            return (
                <Callout tone="amber" icon="clock">
                    <strong>{tooManyAttemptsCopy(retryInSeconds)}</strong> This protects your account from password
                    guessing.
                </Callout>
            );
        case "rejected":
            return <Callout tone="red">{messageFor(failure.error, signInErrorCopy)}</Callout>;
        case "unavailable":
            return (
                <Callout tone="red">
                    <Stack gap="sm">
                        <span>{messageFor(failure.error, signInErrorCopy)}</span>
                        <Row>
                            <Button variant="secondary" size="sm" onClick={onRetry} loading={retrying}>
                                <Icon name="refresh" />
                                Try again
                            </Button>
                        </Row>
                    </Stack>
                </Callout>
            );
    }
}
