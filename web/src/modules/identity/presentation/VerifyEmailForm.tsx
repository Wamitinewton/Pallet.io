"use client";

import { authPaths } from "@/modules/session";
import { classifyRequestFailure, type RequestFailure } from "@/shared/domain/request-failure";
import { messageFor } from "@/shared/presentation/errors";
import { useSecondsUntil } from "@/shared/presentation/hooks";
import { useClock } from "@/shared/presentation/providers";
import {
    AuthForm,
    AuthHeader,
    AuthIcon,
    Button,
    Callout,
    CodeInput,
    Field,
    Icon,
    Row,
    Text,
    VisuallyHidden,
    type CodeInputHandle,
} from "@/shared/presentation/ui";
import Link from "next/link";
import { useRef, useState } from "react";
import { resendAvailableAt, VERIFICATION_CODE_LENGTH, verificationCodeSchema } from "../domain/verification-code";
import { classifyVerificationFailure } from "../domain/verification-failure";
import { EmailAddressStep } from "./EmailAddressStep";
import { CODE_HINT_COPY, CODE_MISMATCH_COPY, CODE_RESENT_COPY } from "./identity-copy";
import { RequestFailureCallout } from "./RequestFailureCallout";
import { useResendVerification, useVerifyEmail } from "./use-verify-email";
import styles from "./VerifyEmailForm.module.css";

const WRONG_CODE_COPY = `${CODE_MISMATCH_COPY} Check the latest email from us, or send a new code.`;

type Notice =
    | { readonly kind: "resent" }
    | { readonly kind: "failed"; readonly failure: RequestFailure; readonly action: "verify" | "resend" };

export interface VerifyEmailFormProps {
    readonly email?: string | undefined;
}

export function VerifyEmailForm({ email: knownEmail }: VerifyEmailFormProps) {
    const [email, setEmail] = useState(knownEmail);
    return email === undefined ? <EmailAddressStep onContinue={setEmail} /> : <CodeStep email={email} />;
}

function CodeStep({ email }: { readonly email: string }) {
    const clock = useClock();
    const codeInput = useRef<CodeInputHandle>(null);
    const [code, setCode] = useState("");
    const [codeError, setCodeError] = useState<string>();
    const [notice, setNotice] = useState<Notice>();
    const [nextResendAt, setNextResendAt] = useState<Date>();
    const verification = useVerifyEmail(email);
    const resend = useResendVerification();

    const failure = notice?.kind === "failed" ? notice.failure : undefined;
    const retryInSeconds = useSecondsUntil(failure?.kind === "rate-limited" ? failure.retryAt : undefined);
    const resendInSeconds = useSecondsUntil(nextResendAt);
    const limited = failure?.kind === "rate-limited" && retryInSeconds > 0;

    const startOver = (message: string | undefined) => {
        setCode("");
        setCodeError(message);
        codeInput.current?.clear();
        codeInput.current?.focus();
    };

    const verify = (value: string) => {
        if (verification.pending || limited) return;
        const parsed = verificationCodeSchema.safeParse(value);
        if (!parsed.success) {
            setCodeError(parsed.error.issues[0]?.message);
            codeInput.current?.focus();
            return;
        }
        setCodeError(undefined);
        setNotice(undefined);
        verification.verify(parsed.data, {
            onError: (error) => {
                const classified = classifyVerificationFailure(error, clock.now());
                if (classified.kind === "wrong-code") startOver(WRONG_CODE_COPY);
                else setNotice({ kind: "failed", failure: classified, action: "verify" });
            },
        });
    };

    const requestNewCode = () => {
        setNotice(undefined);
        resend.resend(email, {
            onSuccess: () => {
                setNotice({ kind: "resent" });
                setNextResendAt(resendAvailableAt(clock.now()));
                startOver(undefined);
            },
            onError: (error) => {
                setNotice({ kind: "failed", failure: classifyRequestFailure(error, clock.now()), action: "resend" });
            },
        });
    };

    return (
        <AuthForm
            onSubmit={(event) => {
                event.preventDefault();
                verify(code);
            }}
            noValidate
        >
            <div>
                <AuthIcon name="mail" />
                <AuthHeader
                    className={styles.header}
                    title="Check your inbox"
                    lede={
                        <>
                            We sent a code to <strong className={styles.email}>{email}</strong>. Enter it below to
                            confirm this is your address.
                        </>
                    }
                />
            </div>

            <VisuallyHidden role="status">
                {notice?.kind === "resent" && CODE_RESENT_COPY}
                {failure && messageFor(failure.error, {}, { reference: false })}
                {codeError}
            </VisuallyHidden>

            {notice?.kind === "resent" && <Callout tone="green">{CODE_RESENT_COPY}</Callout>}
            {notice?.kind === "failed" && (
                <RequestFailureCallout
                    failure={notice.failure}
                    retryInSeconds={retryInSeconds}
                    onRetry={
                        notice.action === "verify"
                            ? () => {
                                  verify(code);
                              }
                            : requestNewCode
                    }
                    retrying={notice.action === "verify" ? verification.pending : resend.pending}
                />
            )}

            <Field label="Verification code" hint={codeError === undefined && CODE_HINT_COPY} error={codeError}>
                <CodeInput
                    ref={codeInput}
                    length={VERIFICATION_CODE_LENGTH}
                    onValueChange={(value) => {
                        setCode(value);
                        if (value !== "") setCodeError(undefined);
                    }}
                    onComplete={verify}
                />
            </Field>

            <Button type="submit" variant="primary" size="lg" block loading={verification.pending} disabled={limited}>
                Confirm email
            </Button>

            <Row justify="between" className={styles.resend}>
                <Text size="sm" tone="muted">
                    Didn&apos;t get it? Check spam, or
                </Text>
                <Button
                    variant="ghost"
                    size="sm"
                    onClick={requestNewCode}
                    loading={resend.pending}
                    disabled={resendInSeconds > 0 || limited}
                >
                    <Icon name="refresh" />
                    {resendInSeconds > 0 ? `Send a new code in ${String(resendInSeconds)}s` : "Send a new code"}
                </Button>
            </Row>

            <Text size="sm" tone="muted">
                Wrong email? <Link href={authPaths.signUp}>Start again</Link>.
            </Text>
        </AuthForm>
    );
}
