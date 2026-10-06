"use client";

import { ApiError } from "@/shared/domain/errors";
import { classifyRequestFailure, type RequestFailure } from "@/shared/domain/request-failure";
import { messageFor, tooManyAttemptsCopy } from "@/shared/presentation/errors";
import { useSecondsUntil } from "@/shared/presentation/hooks";
import { useClock } from "@/shared/presentation/providers";
import {
    Button,
    Callout,
    Dialog,
    DialogBody,
    DialogFooter,
    Field,
    PasswordInput,
    Stack,
} from "@/shared/presentation/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { reauthenticationSchema, type Reauthentication } from "../domain/reauthentication";
import { INVALID_CREDENTIALS } from "../domain/sign-in-failure";
import { sessionKeys } from "./queries";
import { useSessionActions } from "./session-actions";
import { STEP_UP_TITLE_COPY, stepUpDescription, WRONG_PASSWORD_COPY } from "./step-up-copy";
import { useSessionSummary } from "./use-session-summary";

export interface ReauthenticateDialogProps {
    readonly open: boolean;
    readonly onConfirmed: () => void;
    readonly onCancelled: () => void;
}

export function ReauthenticateDialog({ open, onConfirmed, onCancelled }: ReauthenticateDialogProps) {
    return (
        <Dialog
            open={open}
            onOpenChange={(next) => {
                if (!next) onCancelled();
            }}
            title={STEP_UP_TITLE_COPY}
            description={<StepUpDescription />}
        >
            <ReauthenticateForm onConfirmed={onConfirmed} onCancelled={onCancelled} />
        </Dialog>
    );
}

/**
 * Reads the session only while the dialog is open: the dialog is always mounted beside every page, and an
 * earlier read would seed an empty summary query that a page's prefetched one can't hydrate over during SSR.
 */
function StepUpDescription() {
    return stepUpDescription(useSessionSummary().data?.email);
}

type ReauthenticateFormProps = Omit<ReauthenticateDialogProps, "open">;

/** Mounted only while the dialog is open, so every prompt starts empty. */
function ReauthenticateForm({ onConfirmed, onCancelled }: ReauthenticateFormProps) {
    const clock = useClock();
    const queryClient = useQueryClient();
    const { confirmPassword } = useSessionActions();
    const confirm = useMutation({
        mutationFn: confirmPassword,
        gcTime: 0,
        onSuccess: () => queryClient.invalidateQueries({ queryKey: sessionKeys.all }),
    });
    const [failure, setFailure] = useState<RequestFailure>();
    const form = useForm<Reauthentication>({
        resolver: zodResolver(reauthenticationSchema),
        defaultValues: { password: "" },
    });
    const retryInSeconds = useSecondsUntil(failure?.kind === "rate-limited" ? failure.retryAt : undefined);
    const limited = failure?.kind === "rate-limited" && retryInSeconds > 0;
    const { setFocus } = form;

    useEffect(() => {
        setFocus("password");
    }, [setFocus]);

    const submit = form.handleSubmit((values) => {
        setFailure(undefined);
        confirm.mutate(values, {
            onSuccess: onConfirmed,
            onError: (error) => {
                if (error instanceof ApiError && error.is(INVALID_CREDENTIALS)) {
                    form.setError("password", { type: "server", message: WRONG_PASSWORD_COPY }, { shouldFocus: true });
                    return;
                }
                setFailure(classifyRequestFailure(error, clock.now()));
            },
        });
    });

    return (
        <form onSubmit={(event) => void submit(event)} noValidate>
            <DialogBody>
                <Stack>
                    {failure !== undefined && <FailureCallout failure={failure} retryInSeconds={retryInSeconds} />}
                    <Field label="Your password" error={form.formState.errors.password?.message}>
                        <PasswordInput autoComplete="current-password" {...form.register("password")} />
                    </Field>
                </Stack>
            </DialogBody>
            <DialogFooter>
                <Button variant="secondary" onClick={onCancelled} disabled={confirm.isPending}>
                    Cancel
                </Button>
                <Button type="submit" variant="primary" loading={confirm.isPending} disabled={limited}>
                    Confirm
                </Button>
            </DialogFooter>
        </form>
    );
}

function FailureCallout({ failure, retryInSeconds }: Readonly<{ failure: RequestFailure; retryInSeconds: number }>) {
    if (failure.kind !== "rate-limited") {
        return (
            <Callout tone="red" role="alert">
                {messageFor(failure.error)}
            </Callout>
        );
    }
    if (retryInSeconds === 0) return null;
    return (
        <Callout tone="amber" icon="clock" role="alert">
            {tooManyAttemptsCopy(retryInSeconds)}
        </Callout>
    );
}
