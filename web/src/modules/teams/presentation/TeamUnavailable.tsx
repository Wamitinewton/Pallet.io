"use client";

import { useOrganization } from "@/modules/organizations";
import type { OrgId } from "@/shared/domain/ids";
import type { Crumb } from "@/shared/presentation/shell";
import { PageBreadcrumbs } from "@/shared/presentation/shell";
import { Button, EmptyState, Icon, Page } from "@/shared/presentation/ui";
import Link from "next/link";
import { BACK_TO_TEAMS_COPY, TEAM_UNAVAILABLE_COPY, TEAM_UNAVAILABLE_TITLE_COPY, TEAMS_TITLE_COPY } from "./team-copy";
import { orgOverviewPath, teamsPath } from "./team-paths";

export interface TeamUnavailableProps {
    readonly orgId: OrgId;
}

/** For a team that was deleted, or a link that never named one. */
export function TeamUnavailable({ orgId }: TeamUnavailableProps) {
    const orgName = useOrganization(orgId).data?.name;
    const crumbs: Crumb[] = [
        ...(orgName === undefined ? [] : [{ label: orgName, href: orgOverviewPath(orgId) }]),
        { label: TEAMS_TITLE_COPY, href: teamsPath(orgId) },
        { label: TEAM_UNAVAILABLE_TITLE_COPY },
    ];

    return (
        <Page narrow>
            <PageBreadcrumbs items={crumbs} />
            <EmptyState
                icon="teams"
                title={TEAM_UNAVAILABLE_TITLE_COPY}
                description={TEAM_UNAVAILABLE_COPY}
                actions={
                    <Button asChild variant="secondary" size="sm">
                        <Link href={teamsPath(orgId)}>
                            <Icon name="back" />
                            {BACK_TO_TEAMS_COPY}
                        </Link>
                    </Button>
                }
            />
        </Page>
    );
}
