"use client";

import { appsPath } from "@/modules/apps";
import { organizationPath, useOrganization } from "@/modules/organizations";
import { teamsPath } from "@/modules/teams";
import type { OrgId } from "@/shared/domain/ids";
import { ErrorState } from "@/shared/presentation/errors";
import { Panel, Skeleton, Stat, Stats } from "@/shared/presentation/ui";
import type { Route } from "next";
import Link from "next/link";
import { COUNTS_UNAVAILABLE_COPY, LOADING_COUNTS_COPY, STAT_LABEL, statLinkLabel } from "./overview-copy";
import styles from "./Overview.module.css";

export interface StatsRowProps {
    readonly orgId: OrgId;
}

/** The counts come with the organization the shell already read, so they cost no request of their own. */
export function StatsRow({ orgId }: StatsRowProps) {
    const organization = useOrganization(orgId);

    if (organization.data === undefined) {
        return organization.isError ? (
            <Panel>
                <ErrorState
                    error={organization.error}
                    title={COUNTS_UNAVAILABLE_COPY}
                    headingLevel={2}
                    retrying={organization.isFetching}
                    onRetry={() => void organization.refetch()}
                />
            </Panel>
        ) : (
            <Stats role="status" aria-busy="true" aria-label={LOADING_COUNTS_COPY}>
                {Object.keys(STAT_LABEL).map((key) => (
                    <div key={key} className={styles.statsSkeleton}>
                        <Skeleton width={36} height={26} />
                        <Skeleton width={64} height={12} />
                    </div>
                ))}
            </Stats>
        );
    }

    const { apps, members, teams } = organization.data.counts;
    return (
        <Stats>
            <Stat asChild value={apps} label={STAT_LABEL.apps}>
                <Link href={appsPath(orgId)} aria-label={statLinkLabel(apps, STAT_LABEL.apps)} />
            </Stat>
            <Stat asChild value={members} label={STAT_LABEL.members}>
                <Link
                    href={organizationPath(orgId, "members") as Route}
                    aria-label={statLinkLabel(members, STAT_LABEL.members)}
                />
            </Stat>
            <Stat asChild value={teams} label={STAT_LABEL.teams}>
                <Link href={teamsPath(orgId)} aria-label={statLinkLabel(teams, STAT_LABEL.teams)} />
            </Stat>
        </Stats>
    );
}
