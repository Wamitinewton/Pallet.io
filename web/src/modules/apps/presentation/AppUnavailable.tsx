"use client";

import { useOrganization } from "@/modules/organizations";
import type { OrgId } from "@/shared/domain/ids";
import { PageBreadcrumbs, type Crumb } from "@/shared/presentation/shell";
import { Button, EmptyState, Icon, Page } from "@/shared/presentation/ui";
import Link from "next/link";
import { APP_UNAVAILABLE_COPY, APP_UNAVAILABLE_TITLE_COPY, APPS_TITLE_COPY, BACK_TO_APPS_COPY } from "./app-copy";
import { appsPath, orgOverviewPath } from "./app-paths";

export interface AppUnavailableProps {
    readonly orgId: OrgId;
}

/** For an app that was deleted, or a link that never named one. */
export function AppUnavailable({ orgId }: AppUnavailableProps) {
    const orgName = useOrganization(orgId).data?.name;
    const crumbs: Crumb[] = [
        ...(orgName === undefined ? [] : [{ label: orgName, href: orgOverviewPath(orgId) }]),
        { label: APPS_TITLE_COPY, href: appsPath(orgId) },
        { label: APP_UNAVAILABLE_TITLE_COPY },
    ];

    return (
        <Page narrow>
            <PageBreadcrumbs items={crumbs} />
            <EmptyState
                icon="apps"
                title={APP_UNAVAILABLE_TITLE_COPY}
                description={APP_UNAVAILABLE_COPY}
                actions={
                    <Button asChild variant="secondary" size="sm">
                        <Link href={appsPath(orgId)}>
                            <Icon name="back" />
                            {BACK_TO_APPS_COPY}
                        </Link>
                    </Button>
                }
            />
        </Page>
    );
}
