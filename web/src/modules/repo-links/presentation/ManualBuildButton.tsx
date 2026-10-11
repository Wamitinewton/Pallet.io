"use client";

import { githubPath } from "@/modules/github";
import { useOrgAccess } from "@/modules/members";
import { ApiError } from "@/shared/domain/errors";
import type { AppId, OrgId } from "@/shared/domain/ids";
import { classifyRequestFailure } from "@/shared/domain/request-failure";
import { messageFor } from "@/shared/presentation/errors";
import { useIdempotencyKey } from "@/shared/presentation/forms";
import { useSecondsUntil } from "@/shared/presentation/hooks";
import { useClock } from "@/shared/presentation/providers";
import {
    Button,
    Callout,
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
import Link from "next/link";
import { useState } from "react";
import { useForm } from "react-hook-form";
import {
    manualBuildSchema,
    requestedBranch,
    type ManualBuildForm,
    type ManualBuildValues,
} from "../domain/manual-build";
import { isActive, type RepoLink } from "../domain/repo-link";
import { BRANCH_NOT_FOUND, IDEMPOTENCY_KEY_REUSE, INSTALLATION_SUSPENDED } from "../domain/repo-link-errors";
import {
    branchNotFoundCopy,
    BUILD_BRANCH_LABEL_COPY,
    BUILD_LIMIT_COPY,
    buildBranchHintCopy,
    buildErrorCopy,
    buildLimitReachedCopy,
    buildRequestedCopy,
    CANCEL_COPY,
    OPEN_GITHUB_SETTINGS_COPY,
    retryInCopy,
    START_BUILD_COPY,
    START_BUILD_DESCRIPTION_COPY,
    START_BUILD_SUBMIT_COPY,
} from "./repo-link-copy";
import styles from "./RepoLinks.module.css";
import { useRepoLink } from "./use-repo-link";
import { useRequestManualBuild } from "./use-repo-link-mutations";

export interface ManualBuildButtonProps {
    readonly orgId: OrgId;
    readonly appId: AppId;
}

/** Offered to developers and above, for an active link only. */
export function ManualBuildButton({ orgId, appId }: ManualBuildButtonProps) {
    const canBuild = useOrgAccess().can("builds.trigger");
    const link = useRepoLink(orgId, appId).data;

    if (!canBuild || link == null || !isActive(link)) return null;
    return <ManualBuildDialog orgId={orgId} link={link} />;
}

/** The branch the current key was handed out for; `null` before any key was. */
type KeyedBranch = string | undefined | null;

interface ManualBuildDialogProps {
    readonly orgId: OrgId;
    readonly link: RepoLink;
}

/**
 * One idempotency key per intent: a retry of the same branch, after a timeout or a dropped connection, sends
 * the same key and gets the first result back instead of a second build. Choosing another branch is another
 * intent, and so is the next build once one has been accepted.
 */
function ManualBuildDialog({ orgId, link }: ManualBuildDialogProps) {
    const clock = useClock();
    const toast = useToast();
    const key = useIdempotencyKey();
    const [keyedBranch, setKeyedBranch] = useState<KeyedBranch>(null);
    const build = useRequestManualBuild(orgId, link.appId);
    const [open, setOpen] = useState(false);
    const [retryAt, setRetryAt] = useState<Date>();
    const secondsLeft = useSecondsUntil(retryAt);
    const limited = secondsLeft > 0;
    const { productionBranch } = link;
    const form = useForm<ManualBuildForm, unknown, ManualBuildValues>({
        resolver: zodResolver(manualBuildSchema),
        defaultValues: { branch: productionBranch },
    });

    const startNewIntent = () => {
        key.renew();
        setKeyedBranch(null);
    };

    const submit = form.handleSubmit(({ branch }) => {
        if (limited || build.isPending) return;
        const requested = requestedBranch(branch, productionBranch);
        if (keyedBranch !== requested) {
            key.renew();
            setKeyedBranch(requested);
        }
        build.mutate(
            { productionBranch, form: { branch: branch ?? "" }, idempotencyKey: key.current() },
            {
                onSuccess: (result) => {
                    startNewIntent();
                    setOpen(false);
                    form.reset({ branch: productionBranch });
                    toast.success(buildRequestedCopy(result.commitSha, result.branch));
                },
                onError: (error) => {
                    const failure = classifyRequestFailure(error, clock.now());
                    if (failure.kind === "rate-limited") setRetryAt(failure.retryAt);
                    if (error instanceof ApiError && error.is(IDEMPOTENCY_KEY_REUSE)) startNewIntent();
                    if (error instanceof ApiError && error.is(BRANCH_NOT_FOUND)) {
                        form.setError(
                            "branch",
                            { type: "server", message: branchNotFoundCopy(branch ?? productionBranch) },
                            { shouldFocus: true },
                        );
                    }
                },
            },
        );
    });

    const failure = build.error;
    const showFailure =
        failure !== null &&
        !(failure instanceof ApiError && failure.is(BRANCH_NOT_FOUND)) &&
        classifyRequestFailure(failure, clock.now()).kind !== "rate-limited";

    return (
        <Dialog
            open={open}
            onOpenChange={(next) => {
                if (build.isPending) return;
                setOpen(next);
                if (!next) build.reset();
            }}
            trigger={
                <Button variant="primary">
                    <Icon name="play" />
                    {START_BUILD_COPY}
                </Button>
            }
            title={START_BUILD_COPY}
            description={START_BUILD_DESCRIPTION_COPY}
            onSubmit={(event) => void submit(event)}
        >
            <DialogBody>
                <Stack>
                    {limited && (
                        <Callout tone="amber" role="alert">
                            {buildLimitReachedCopy(secondsLeft)}
                        </Callout>
                    )}
                    {showFailure && (
                        <Callout tone="red" role="alert">
                            <Stack gap="sm">
                                <span>{messageFor(failure, buildErrorCopy)}</span>
                                {failure instanceof ApiError && failure.is(INSTALLATION_SUSPENDED) && (
                                    <span>
                                        <Link href={githubPath(orgId)}>{OPEN_GITHUB_SETTINGS_COPY}</Link>
                                    </span>
                                )}
                            </Stack>
                        </Callout>
                    )}
                    <Field
                        label={BUILD_BRANCH_LABEL_COPY}
                        hint={buildBranchHintCopy(productionBranch)}
                        error={form.formState.errors.branch?.message}
                    >
                        <Input
                            mono
                            spellCheck={false}
                            autoComplete="off"
                            autoCapitalize="off"
                            {...form.register("branch")}
                        />
                    </Field>
                    <p className={styles.explanation}>{BUILD_LIMIT_COPY}</p>
                </Stack>
            </DialogBody>
            <DialogFooter>
                <DialogClose>
                    <Button variant="secondary" disabled={build.isPending}>
                        {CANCEL_COPY}
                    </Button>
                </DialogClose>
                <Button type="submit" variant="primary" loading={build.isPending} disabled={limited}>
                    <Icon name="play" />
                    {limited ? retryInCopy(secondsLeft) : START_BUILD_SUBMIT_COPY}
                </Button>
            </DialogFooter>
        </Dialog>
    );
}
