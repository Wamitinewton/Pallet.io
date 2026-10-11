"use client";

import { useOrgAccess } from "@/modules/members";
import type { AppId, OrgId } from "@/shared/domain/ids";
import { Badge, Button, Icon } from "@/shared/presentation/ui";
import Link from "next/link";
import { linkHealth } from "../domain/link-health";
import { LIST_BADGE_STALE_MS } from "./queries";
import {
    LINK_REPOSITORY_SHORT_COPY,
    linkRepositoryForCopy,
    NO_REPOSITORY_TITLE_COPY,
    STATUS_LABEL,
    UNAVAILABLE_BADGE_COPY,
} from "./repo-link-copy";
import { connectRepositoryPath } from "./repo-link-paths";
import styles from "./RepoLinks.module.css";
import { RepoStatusBadge } from "./RepoStatusBadge";
import { useRepoLink } from "./use-repo-link";

export interface AppRepositoryCellProps {
    readonly orgId: OrgId;
    readonly appId: AppId;
    readonly appName: string;
}

/**
 * One row's repository in the apps list. Only the rows on screen mount, so the page reads at most a page of
 * links, each trusted for a minute; a batch endpoint would make this one request.
 */
export function AppRepositoryCell({ orgId, appId, appName }: AppRepositoryCellProps) {
    const canLink = useOrgAccess().can("repoLink.link");
    const link = useRepoLink(orgId, appId, { staleTime: LIST_BADGE_STALE_MS });

    if (link.isError) return <Badge outline>{UNAVAILABLE_BADGE_COPY}</Badge>;
    if (link.data === undefined) return <Badge outline>{STATUS_LABEL.checking}</Badge>;

    const health = linkHealth(link.data);
    if (health.kind === "unlinked") {
        return canLink ? (
            <Button asChild variant="secondary" size="sm">
                <Link href={connectRepositoryPath(orgId, appId)} aria-label={linkRepositoryForCopy(appName)}>
                    <Icon name="link" />
                    {LINK_REPOSITORY_SHORT_COPY}
                </Link>
            </Button>
        ) : (
            <Badge outline>{NO_REPOSITORY_TITLE_COPY}</Badge>
        );
    }

    const { repoFullName, rootDirectory } = health.link;
    return (
        <div className={styles.cell}>
            <RepoStatusBadge health={health} />
            <span className={styles.cellRepo}>
                {rootDirectory === "" ? repoFullName : `${repoFullName} · ${rootDirectory}`}
            </span>
        </div>
    );
}
