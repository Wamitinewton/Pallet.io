"use client";

import { useApp } from "@/modules/apps";
import { githubPath, installationSettingsUrl, useOrgInstallations, type InstallationLink } from "@/modules/github";
import { useOrgAccess } from "@/modules/members";
import type { AppId, OrgId } from "@/shared/domain/ids";
import { ErrorState } from "@/shared/presentation/errors";
import {
    Badge,
    Button,
    Callout,
    Copyable,
    EmptyState,
    Eyebrow,
    Icon,
    Panel,
    PanelBody,
    PanelHead,
    RelativeTime,
    Row,
    SectionGap,
    Sha,
    Skeleton,
    Stack,
} from "@/shared/presentation/ui";
import Link from "next/link";
import type { ReactNode } from "react";
import { linkHealth, type LinkHealth, type UnlinkedRepository } from "../domain/link-health";
import { commitUrl, repositoryUrl } from "../domain/repo-link";
import { AccessPanel } from "./AccessPanel";
import { BuildSettingsPanel } from "./BuildSettingsPanel";
import { DisconnectRepoPanel } from "./DisconnectRepoPanel";
import {
    ADMIN_CAN_RELINK_COPY,
    ARCHIVED_COPY,
    ARCHIVED_TITLE_COPY,
    COPY_SHA_LABEL_COPY,
    DISCONNECTED_ON_COPY,
    disconnectedTitleCopy,
    disconnectReasonCopy,
    LATEST_PUSH_COPY,
    LINK_A_REPOSITORY_COPY,
    LINK_AGAIN_COPY,
    LOADING_LINK_COPY,
    NO_PUSH_YET_COPY,
    NO_REPOSITORY_COPY,
    NO_REPOSITORY_TITLE_COPY,
    ON_COPY,
    OPEN_GITHUB_SETTINGS_COPY,
    previouslyLinkedCopy,
    REPOSITORY_LABEL_COPY,
    throughInstallationCopy,
    UNLINKED_ADMIN_HINT_COPY,
    UNLINKED_COPY,
    UNLINKED_TITLE_COPY,
    UPDATE_ACCESS_ON_GITHUB_COPY,
} from "./repo-link-copy";
import { connectRepositoryPath } from "./repo-link-paths";
import styles from "./RepoLinks.module.css";
import { RepoStatusBadge } from "./RepoStatusBadge";
import { useRepoLink } from "./use-repo-link";

export interface RepositoryPanelProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    readonly appId: AppId;
}

/** The app page's repository tab, drawn from the link's health; a failed read is an error, never a state. */
export function RepositoryPanel({ orgId, orgName, appId }: RepositoryPanelProps) {
    const link = useRepoLink(orgId, appId);

    if (link.data === undefined) {
        return link.isError ? (
            <Panel>
                <ErrorState
                    error={link.error}
                    headingLevel={2}
                    retrying={link.isFetching}
                    onRetry={() => void link.refetch()}
                />
            </Panel>
        ) : (
            <div className={styles.detail} role="status" aria-busy="true" aria-label={LOADING_LINK_COPY}>
                <Skeleton height={220} />
                <Skeleton height={180} />
            </div>
        );
    }

    const health = linkHealth(link.data);
    if (health.kind === "unlinked") {
        return <UnlinkedState orgId={orgId} appId={appId} {...(health.previous && { previous: health.previous })} />;
    }
    return <LinkedState orgId={orgId} orgName={orgName} appId={appId} health={health} />;
}

interface UnlinkedStateProps {
    readonly orgId: OrgId;
    readonly appId: AppId;
    readonly previous?: UnlinkedRepository;
}

function UnlinkedState({ orgId, appId, previous }: UnlinkedStateProps) {
    const canLink = useOrgAccess().can("repoLink.link");
    const lastLinked = previous && (
        <span className={styles.previous}>
            {previouslyLinkedCopy(previous.repoFullName)} <RelativeTime dateTime={previous.unlinkedAt} />.
        </span>
    );

    return (
        <Panel className={styles.unlinked}>
            <EmptyState
                icon="github"
                title={canLink ? UNLINKED_TITLE_COPY : NO_REPOSITORY_TITLE_COPY}
                description={
                    <>
                        {canLink ? UNLINKED_COPY : NO_REPOSITORY_COPY} {lastLinked}
                    </>
                }
                actions={
                    canLink && (
                        <Stack gap="sm" className={styles.unlinkedActions}>
                            <Button asChild variant="primary">
                                <Link href={connectRepositoryPath(orgId, appId)}>
                                    <Icon name="link" />
                                    {LINK_A_REPOSITORY_COPY}
                                </Link>
                            </Button>
                            <span className={styles.faint}>{UNLINKED_ADMIN_HINT_COPY}</span>
                        </Stack>
                    )
                }
            />
        </Panel>
    );
}

type Linked = Exclude<LinkHealth, { kind: "unlinked" }>;

interface LinkedStateProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    readonly appId: AppId;
    readonly health: Linked;
}

function LinkedState({ orgId, orgName, appId, health }: LinkedStateProps) {
    const access = useOrgAccess();
    const appName = useApp(orgId, appId).data?.name;
    const installation = useOrgInstallations(orgId).data?.items.find(
        ({ installationId }) => installationId === health.link.installationId,
    );
    const active = health.kind !== "disconnected";

    return (
        <div className={styles.detail}>
            <SectionGap>
                {health.kind === "disconnected" && (
                    <DisconnectedCallout orgId={orgId} orgName={orgName} health={health} installation={installation} />
                )}
                {health.kind === "connected-with-warnings" && (
                    <Callout tone="amber" icon="archive">
                        <strong>{ARCHIVED_TITLE_COPY}</strong> {ARCHIVED_COPY}
                    </Callout>
                )}
                <RepositorySummary health={health} installation={installation} />
                <BuildSettingsPanel orgId={orgId} link={health.link} active={active} />
            </SectionGap>
            <SectionGap>
                <AccessPanel orgId={orgId} health={health} />
                {active && access.can("repoLink.disconnect") && (
                    <DisconnectRepoPanel orgId={orgId} link={health.link} appName={appName} />
                )}
            </SectionGap>
        </div>
    );
}

interface RepositorySummaryProps {
    readonly health: Linked;
    readonly installation: InstallationLink | undefined;
}

function RepositorySummary({ health, installation }: RepositorySummaryProps) {
    const { repoFullName, lastAcceptedHead: head } = health.link;

    return (
        <Panel aria-label={REPOSITORY_LABEL_COPY}>
            <PanelHead>
                <div className={styles.repoHead}>
                    <span className={styles.ghMark} aria-hidden="true">
                        <Icon name="github" />
                    </span>
                    <div className={styles.repoHeadText}>
                        <a href={repositoryUrl(repoFullName)} className={styles.repoName}>
                            {repoFullName}
                        </a>
                        <div className={styles.through}>{throughInstallationCopy(installation?.accountLogin)}</div>
                    </div>
                </div>
                <RepoStatusBadge health={health} />
            </PanelHead>
            {health.kind !== "disconnected" && (
                <PanelBody>
                    <Eyebrow className={styles.eyebrow}>{LATEST_PUSH_COPY}</Eyebrow>
                    {head === null ? (
                        <p className={styles.muted}>{NO_PUSH_YET_COPY}</p>
                    ) : (
                        <div className={styles.headCommit}>
                            <Icon name="commit" />
                            <div className={styles.headCommitText}>
                                <div className={styles.headSummary}>
                                    <Copyable
                                        value={head.sha}
                                        label={COPY_SHA_LABEL_COPY}
                                        display={
                                            <a href={commitUrl(repoFullName, head.sha)} className={styles.shaLink}>
                                                <Sha value={head.sha} />
                                            </a>
                                        }
                                    />
                                    {head.message !== null && (
                                        <span className={styles.headMessage} title={head.message}>
                                            {head.message}
                                        </span>
                                    )}
                                </div>
                                <div className={styles.headLine}>
                                    {head.author !== null && (
                                        <>
                                            <span className={styles.headAuthor}>{head.author}</span>
                                            <span className={styles.faint} aria-hidden="true">
                                                ·
                                            </span>
                                        </>
                                    )}
                                    <span className={styles.muted}>{ON_COPY}</span>
                                    <Badge outline className={styles.mono}>
                                        {head.branch}
                                    </Badge>
                                    <span className={styles.faint} aria-hidden="true">
                                        ·
                                    </span>
                                    <RelativeTime dateTime={head.advancedAt} className={styles.when} />
                                </div>
                            </div>
                        </div>
                    )}
                </PanelBody>
            )}
        </Panel>
    );
}

interface DisconnectedCalloutProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    readonly health: Extract<LinkHealth, { kind: "disconnected" }>;
    readonly installation: InstallationLink | undefined;
}

function DisconnectedCallout({ orgId, orgName, health, installation }: DisconnectedCalloutProps) {
    const canLink = useOrgAccess().can("repoLink.link");
    const { link, reason, remedy, disconnectedAt } = health;

    const remedyAction = (): ReactNode => {
        switch (remedy) {
            case "relink":
                return null;
            case "reconnect-installation":
                return (
                    <Button asChild variant="secondary" size="sm">
                        <Link href={githubPath(orgId)}>{OPEN_GITHUB_SETTINGS_COPY}</Link>
                    </Button>
                );
            case "restore-access":
                return (
                    installation !== undefined && (
                        <Button asChild variant="secondary" size="sm">
                            <a href={installationSettingsUrl(installation)}>
                                {UPDATE_ACCESS_ON_GITHUB_COPY}
                                <Icon name="external" />
                            </a>
                        </Button>
                    )
                );
        }
    };

    return (
        <Callout tone="red" icon="unlink" role="alert">
            <Stack gap="sm">
                <span>
                    <strong>{disconnectedTitleCopy(link.repoFullName)}</strong>{" "}
                    {disconnectReasonCopy(reason, { orgName, verifierLogin: link.verification?.githubLogin })}
                    {!canLink && ` ${ADMIN_CAN_RELINK_COPY}`}
                </span>
                <Row className={styles.actions}>
                    {remedyAction()}
                    {canLink && (
                        <Button asChild variant="ghost" size="sm">
                            <Link href={connectRepositoryPath(orgId, link.appId)}>{LINK_AGAIN_COPY}</Link>
                        </Button>
                    )}
                </Row>
                <span className={styles.when}>
                    {DISCONNECTED_ON_COPY} <RelativeTime dateTime={disconnectedAt} />.
                </span>
            </Stack>
        </Callout>
    );
}
