"use client";

import type { OrgId } from "@/shared/domain/ids";
import { messageFor } from "@/shared/presentation/errors";
import {
    Button,
    Callout,
    Icon,
    Panel,
    PanelBody,
    PanelHead,
    Person,
    Skeleton,
    Stack,
    useToast,
} from "@/shared/presentation/ui";
import {
    LOADING_SESSION_COPY,
    NOT_SIGNED_IN_COPY,
    NOT_SIGNED_IN_META_COPY,
    SESSION_DESCRIPTION_COPY,
    SESSION_TITLE_COPY,
    SESSION_UNAVAILABLE_COPY,
    SIGN_IN_WITH_GITHUB_COPY,
    SIGN_OUT_OF_GITHUB_COPY,
    SIGNED_OUT_OF_GITHUB_COPY,
    signedInUntilCopy,
    startErrorCopy,
    TRY_AGAIN_COPY,
} from "./github-copy";
import { githubPath } from "./github-paths";
import styles from "./GitHub.module.css";
import { GitHubAvatar } from "./GitHubAvatar";
import { useEndGitHubSession } from "./use-github-mutations";
import { useStartAuthorization } from "./use-github-redirect";
import { useGitHubSession } from "./use-github-session";

export interface GitHubSessionPanelProps {
    readonly orgId: OrgId;
}

export function GitHubSessionPanel({ orgId }: GitHubSessionPanelProps) {
    return (
        <Panel aria-label={SESSION_TITLE_COPY}>
            <PanelHead title={SESSION_TITLE_COPY} description={SESSION_DESCRIPTION_COPY} />
            <PanelBody>
                <SessionBody orgId={orgId} />
            </PanelBody>
        </Panel>
    );
}

function SessionBody({ orgId }: GitHubSessionPanelProps) {
    const session = useGitHubSession();
    const toast = useToast();
    const end = useEndGitHubSession();
    const authorize = useStartAuthorization();

    if (session.data === undefined) {
        if (session.isError) {
            return (
                <div className={styles.sessionRow} role="alert">
                    <span>{SESSION_UNAVAILABLE_COPY}</span>
                    <Button
                        variant="secondary"
                        size="sm"
                        loading={session.isFetching}
                        onClick={() => void session.refetch()}
                    >
                        {TRY_AGAIN_COPY}
                    </Button>
                </div>
            );
        }
        return (
            <div className={styles.sessionRow} role="status" aria-label={LOADING_SESSION_COPY}>
                <Skeleton width={220} height={32} />
                <Skeleton width={140} height={30} />
            </div>
        );
    }

    if (session.data === null) {
        return (
            <Stack gap="sm">
                {authorize.error !== null && (
                    <Callout tone="red" role="alert">
                        {messageFor(authorize.error, startErrorCopy)}
                    </Callout>
                )}
                <div className={styles.sessionRow}>
                    <Person
                        avatar={
                            <span className={styles.mark} style={{ width: 32, height: 32 }} aria-hidden="true">
                                <Icon name="github" />
                            </span>
                        }
                        name={NOT_SIGNED_IN_COPY}
                        meta={NOT_SIGNED_IN_META_COPY}
                    />
                    <Button
                        variant="secondary"
                        size="sm"
                        loading={authorize.pending}
                        onClick={() => {
                            authorize.start({ returnTo: githubPath(orgId) });
                        }}
                    >
                        <Icon name="github" />
                        {SIGN_IN_WITH_GITHUB_COPY}
                    </Button>
                </div>
            </Stack>
        );
    }

    const { githubLogin, expiresAt } = session.data;
    return (
        <Stack gap="sm">
            {end.isError && (
                <Callout tone="red" role="alert">
                    {messageFor(end.error)}
                </Callout>
            )}
            <div className={styles.sessionRow}>
                <Person
                    avatar={<GitHubAvatar login={githubLogin} />}
                    name={<span className={styles.login}>@{githubLogin}</span>}
                    meta={<time dateTime={expiresAt}>{signedInUntilCopy(expiresAt)}</time>}
                />
                <Button
                    variant="secondary"
                    size="sm"
                    loading={end.isPending}
                    onClick={() => {
                        end.mutate(undefined, {
                            onSuccess: () => {
                                toast.success(SIGNED_OUT_OF_GITHUB_COPY);
                            },
                        });
                    }}
                >
                    {SIGN_OUT_OF_GITHUB_COPY}
                </Button>
            </div>
        </Stack>
    );
}
