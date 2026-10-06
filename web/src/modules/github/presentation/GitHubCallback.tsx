"use client";

import { messageFor } from "@/shared/presentation/errors";
import { useFocusOnMount } from "@/shared/presentation/hooks";
import { AuthCard, AuthHeader, AuthIcon, Button, Callout, Spinner, type IconName } from "@/shared/presentation/ui";
import { useQueryClient } from "@tanstack/react-query";
import type { Route } from "next";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useId, useRef, useState, type ReactNode } from "react";
import { callbackQueryFrom, type GitHubRedirectOutcome } from "../domain/callback";
import {
    BACK_TO_GITHUB_PAGE_COPY,
    CALLBACK_AUTHORIZING_COPY,
    CALLBACK_AUTHORIZING_TITLE_COPY,
    CALLBACK_CANCELLED_COPY,
    CALLBACK_CANCELLED_TITLE_COPY,
    CALLBACK_EXPIRED_COPY,
    CALLBACK_EXPIRED_TITLE_COPY,
    CALLBACK_FAILED_TITLE_COPY,
    CALLBACK_INVALID_COPY,
    CALLBACK_INVALID_TITLE_COPY,
    CALLBACK_REQUESTED_COPY,
    CALLBACK_REQUESTED_TITLE_COPY,
    CALLBACK_START_AGAIN_COPY,
    CALLBACK_START_AGAIN_TITLE_COPY,
    CALLBACK_WORKING_COPY,
    CALLBACK_WORKING_TITLE_COPY,
    linkErrorCopy,
    OPEN_PALLET_COPY,
    startErrorCopy,
    TRY_AGAIN_COPY,
} from "./github-copy";
import { GITHUB_CALLBACK_PATH } from "./github-paths";
import { leaveForGitHub } from "./leave-for-github";
import { githubKeys } from "./queries";
import { useFinishGitHubRedirect, useRetryGitHubRedirect } from "./use-github-redirect";

/**
 * GitHub's redirect target for both authorization and installs. It takes `code` and `state` off the address
 * before anything else, so they never stay in the address bar or history, and settles the redirect once:
 * a strict-mode remount must not spend a single-use state twice.
 */
export function GitHubCallback() {
    const finish = useFinishGitHubRedirect();
    const router = useRouter();
    const queryClient = useQueryClient();
    const started = useRef(false);
    const [outcome, setOutcome] = useState<GitHubRedirectOutcome>();

    useEffect(() => {
        if (started.current) return;
        started.current = true;
        const query = callbackQueryFrom(new URLSearchParams(window.location.search));
        window.history.replaceState(null, "", GITHUB_CALLBACK_PATH);
        finish(query).then(setOutcome, () => undefined);
    }, [finish]);

    useEffect(() => {
        if (outcome === undefined) return;
        switch (outcome.kind) {
            case "connected":
                void queryClient.invalidateQueries({ queryKey: githubKeys.mine() });
                router.replace(outcome.destination as Route);
                return;
            case "installed":
                void queryClient.invalidateQueries({ queryKey: githubKeys.mine() });
                void queryClient.invalidateQueries({ queryKey: githubKeys.org(outcome.orgId) });
                router.replace(outcome.destination as Route);
                return;
            case "authorizing":
                leaveForGitHub(outcome.handoff.url);
                return;
            case "installRequested":
            case "cancelled":
            case "invalid":
            case "startAgain":
            case "stateRejected":
            case "failed":
                return;
        }
    }, [outcome, queryClient, router]);

    return <CallbackCard outcome={outcome} />;
}

function CallbackCard({ outcome }: Readonly<{ outcome: GitHubRedirectOutcome | undefined }>) {
    switch (outcome?.kind) {
        case undefined:
        case "connected":
        case "installed":
            return <WorkingCard title={CALLBACK_WORKING_TITLE_COPY} lede={CALLBACK_WORKING_COPY} />;
        case "authorizing":
            return <WorkingCard title={CALLBACK_AUTHORIZING_TITLE_COPY} lede={CALLBACK_AUTHORIZING_COPY} />;
        case "installRequested":
            return (
                <OutcomeCard
                    icon="clock"
                    tone="amber"
                    title={CALLBACK_REQUESTED_TITLE_COPY}
                    lede={CALLBACK_REQUESTED_COPY}
                >
                    <BackLink to={outcome.backTo} />
                </OutcomeCard>
            );
        case "cancelled":
            return (
                <OutcomeCard icon="x" title={CALLBACK_CANCELLED_TITLE_COPY} lede={CALLBACK_CANCELLED_COPY}>
                    <BackLink to={outcome.backTo} />
                </OutcomeCard>
            );
        case "invalid":
            return (
                <OutcomeCard icon="alert" tone="amber" title={CALLBACK_INVALID_TITLE_COPY} lede={CALLBACK_INVALID_COPY}>
                    <BackLink to={outcome.backTo} />
                </OutcomeCard>
            );
        case "startAgain":
            return (
                <OutcomeCard
                    icon="refresh"
                    tone="amber"
                    title={CALLBACK_START_AGAIN_TITLE_COPY}
                    lede={CALLBACK_START_AGAIN_COPY}
                >
                    <Button asChild variant="primary" block>
                        <Link href={outcome.backTo as Route}>{OPEN_PALLET_COPY}</Link>
                    </Button>
                </OutcomeCard>
            );
        case "stateRejected":
            return (
                <OutcomeCard icon="clock" tone="amber" title={CALLBACK_EXPIRED_TITLE_COPY} lede={CALLBACK_EXPIRED_COPY}>
                    <RetryActions outcome={outcome} />
                </OutcomeCard>
            );
        case "failed":
            return (
                <OutcomeCard
                    icon="alert"
                    tone="amber"
                    title={CALLBACK_FAILED_TITLE_COPY}
                    lede={messageFor(outcome.error, linkErrorCopy)}
                >
                    <RetryActions outcome={outcome} />
                </OutcomeCard>
            );
    }
}

function WorkingCard({ title, lede }: Readonly<{ title: string; lede: string }>) {
    return (
        <AuthCard role="status" aria-label={title}>
            <AuthIcon name="github" />
            <AuthHeader title={title} lede={lede} />
            <Spinner size={20} />
        </AuthCard>
    );
}

interface OutcomeCardProps {
    readonly icon: IconName;
    readonly tone?: "blue" | "amber";
    readonly title: string;
    readonly lede: string;
    readonly children: ReactNode;
}

/** Replaces the working card, so it takes focus for anyone following along with a screen reader. */
function OutcomeCard({ icon, tone = "blue", title, lede, children }: OutcomeCardProps) {
    const card = useFocusOnMount<HTMLDivElement>(true);
    const headerId = useId();

    return (
        <AuthCard ref={card} tabIndex={-1} aria-labelledby={headerId}>
            <AuthIcon name={icon} tone={tone} />
            <AuthHeader id={headerId} title={title} lede={lede} />
            {children}
        </AuthCard>
    );
}

function BackLink({ to }: Readonly<{ to: string }>) {
    return (
        <Button asChild variant="secondary" block>
            <Link href={to as Route}>{BACK_TO_GITHUB_PAGE_COPY}</Link>
        </Button>
    );
}

function RetryActions({
    outcome,
}: Readonly<{ outcome: Extract<GitHubRedirectOutcome, { kind: "stateRejected" | "failed" }> }>) {
    const retry = useRetryGitHubRedirect();

    return (
        <>
            {retry.error !== null && (
                <Callout tone="red" role="alert">
                    {messageFor(retry.error, startErrorCopy)}
                </Callout>
            )}
            <Button
                variant="primary"
                block
                loading={retry.pending}
                onClick={() => {
                    retry.start(outcome.retry);
                }}
            >
                {TRY_AGAIN_COPY}
            </Button>
            <BackLink to={outcome.backTo} />
        </>
    );
}
