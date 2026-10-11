"use client";

import { appPath } from "@/modules/apps";
import { useGitHubSession, useStartAuthorization } from "@/modules/github";
import { useMemberDirectory, useOrgAccess } from "@/modules/members";
import type { OrgId } from "@/shared/domain/ids";
import { messageFor } from "@/shared/presentation/errors";
import {
    Button,
    Callout,
    Icon,
    KeyValue,
    KeyValueRow,
    Panel,
    PanelBody,
    PanelFoot,
    PanelHead,
    Person,
    RelativeTime,
    Stack,
    useToast,
} from "@/shared/presentation/ui";
import { useId } from "react";
import type { LinkHealth } from "../domain/link-health";
import type { RepoLink, Verification } from "../domain/repo-link";
import {
    ACCESS_CONFIRMED_LABEL_COPY,
    ACCESS_EXPLANATION_COPY,
    ACCESS_TITLE_COPY,
    ADMINS_ONLY_COPY,
    CHECK_FAILED_COPY,
    confirmedOnCopy,
    FORMER_MEMBER_COPY,
    LAST_CHECKED_LABEL_COPY,
    NOBODY_VOUCHES_COPY,
    PERMISSION_LABEL,
    TAKE_OVER_COPY,
    TAKE_OVER_SIGN_IN_COPY,
    takeOverErrorCopy,
    tookOverCopy,
    vouchedAsCopy,
    vouchedByCopy,
} from "./repo-link-copy";
import styles from "./RepoLinks.module.css";
import { useTakeOverVerification } from "./use-repo-link-mutations";

export interface AccessPanelProps {
    readonly orgId: OrgId;
    readonly health: Exclude<LinkHealth, { kind: "unlinked" }>;
}

/** Who vouches on GitHub that this app may build the repository, and the way to hand that over. */
export function AccessPanel({ orgId, health }: AccessPanelProps) {
    const { link } = health;
    const canTakeOver = useOrgAccess().can("repoLink.takeOverVerification") && health.kind !== "disconnected";
    const checkFailed = health.kind === "disconnected" && health.reason === "VERIFIER_ACCESS_LOST";
    const takeOver = useTakeOver(orgId, link);
    const hintId = useId();

    return (
        <Panel aria-label={ACCESS_TITLE_COPY}>
            <PanelHead title={ACCESS_TITLE_COPY} />
            <PanelBody>
                <Stack>
                    {takeOver.failure !== null && (
                        <Callout tone="red" role="alert">
                            {messageFor(takeOver.failure, takeOverErrorCopy)}
                        </Callout>
                    )}
                    {link.verification === null ? (
                        <p className={styles.muted}>{NOBODY_VOUCHES_COPY}</p>
                    ) : (
                        <VerificationFacts orgId={orgId} verification={link.verification} checkFailed={checkFailed} />
                    )}
                    <p className={styles.explanation}>{ACCESS_EXPLANATION_COPY}</p>
                    {canTakeOver && takeOver.signedOut && (
                        <p id={hintId} className={styles.explanation}>
                            {TAKE_OVER_SIGN_IN_COPY}
                        </p>
                    )}
                </Stack>
            </PanelBody>
            {canTakeOver && (
                <PanelFoot className={styles.footSpread}>
                    <span>{ADMINS_ONLY_COPY}</span>
                    <Button
                        variant="secondary"
                        size="sm"
                        loading={takeOver.pending}
                        disabled={takeOver.checkingSession}
                        aria-describedby={takeOver.signedOut ? hintId : undefined}
                        onClick={takeOver.start}
                    >
                        <Icon name={takeOver.signedOut ? "github" : "refresh"} />
                        {TAKE_OVER_COPY}
                    </Button>
                </PanelFoot>
            )}
        </Panel>
    );
}

interface VerificationFactsProps {
    readonly orgId: OrgId;
    readonly verification: Verification;
    readonly checkFailed: boolean;
}

function VerificationFacts({ orgId, verification, checkFailed }: VerificationFactsProps) {
    const members = useMemberDirectory(orgId);
    const { verifiedByUserId, githubLogin, permission, accessVerifiedAt, accessCheckedAt } = verification;
    const name = members === undefined ? undefined : (members.get(verifiedByUserId)?.displayName ?? FORMER_MEMBER_COPY);

    return (
        <>
            <Person
                avatar={
                    <span className={styles.shield} aria-hidden="true">
                        <Icon name="shield" />
                    </span>
                }
                name={vouchedByCopy(name)}
                meta={vouchedAsCopy(githubLogin, PERMISSION_LABEL[permission])}
            />
            <KeyValue className={styles.accessFacts}>
                <KeyValueRow term={ACCESS_CONFIRMED_LABEL_COPY}>
                    <time dateTime={accessVerifiedAt}>{confirmedOnCopy(accessVerifiedAt)}</time>
                </KeyValueRow>
                <KeyValueRow term={LAST_CHECKED_LABEL_COPY}>
                    {checkFailed ? (
                        <span className={styles.failed}>
                            {CHECK_FAILED_COPY} <RelativeTime dateTime={accessCheckedAt} />
                        </span>
                    ) : (
                        <RelativeTime dateTime={accessCheckedAt} />
                    )}
                </KeyValueRow>
            </KeyValue>
        </>
    );
}

interface TakeOver {
    readonly start: () => void;
    readonly pending: boolean;
    /** True when the next start goes to GitHub to sign in, and comes back here, before anything is checked. */
    readonly signedOut: boolean;
    readonly checkingSession: boolean;
    readonly failure: unknown;
}

/** Taking over checks the caller's own GitHub access, so without a GitHub session it signs in first. */
function useTakeOver(orgId: OrgId, link: RepoLink): TakeOver {
    const toast = useToast();
    const { appId, repoFullName } = link;
    const session = useGitHubSession();
    const authorize = useStartAuthorization();
    const takeOver = useTakeOverVerification(orgId, appId);
    const signedOut = session.data === null;

    return {
        signedOut,
        checkingSession: session.data === undefined && session.isPending,
        pending: takeOver.isPending || authorize.pending,
        failure: takeOver.error ?? authorize.error,
        start: () => {
            if (signedOut) {
                takeOver.reset();
                authorize.start({ returnTo: appPath(orgId, appId) });
                return;
            }
            takeOver.mutate(undefined, {
                onSuccess: () => {
                    toast.success(tookOverCopy(repoFullName));
                },
            });
        },
    };
}
