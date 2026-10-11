"use client";

import { CreateAppDialog } from "@/modules/apps";
import { InvitePeopleButton, InvitesProvider } from "@/modules/invites";
import { useOrgAccess } from "@/modules/members";
import { organizationPath, useOrganization } from "@/modules/organizations";
import type { OrgId } from "@/shared/domain/ids";
import { PageBreadcrumbs } from "@/shared/presentation/shell";
import { Button, Icon, Page, PageHead, SectionGap } from "@/shared/presentation/ui";
import type { Route } from "next";
import { AttentionList } from "./AttentionList";
import { NEW_APP_COPY, organizationSummaryCopy, OVERVIEW_TITLE_COPY } from "./overview-copy";
import styles from "./Overview.module.css";
import { RecentApps } from "./RecentApps";
import { StatsRow } from "./StatsRow";

export interface OverviewViewProps {
    readonly orgId: OrgId;
    readonly orgName: string;
}

/** Every panel reads, waits and fails on its own, so one service being down never blanks the page. */
export function OverviewView({ orgId, orgName }: OverviewViewProps) {
    const canCreateApps = useOrgAccess().can("apps.create");

    return (
        <InvitesProvider orgId={orgId} orgName={orgName}>
            <Page>
                <PageBreadcrumbs
                    items={[{ label: orgName, href: organizationPath(orgId) as Route }, { label: OVERVIEW_TITLE_COPY }]}
                />
                <PageHead
                    title={orgName}
                    description={<OrganizationSummary orgId={orgId} />}
                    actions={
                        <>
                            <InvitePeopleButton variant="secondary" />
                            {canCreateApps && (
                                <CreateAppDialog
                                    orgId={orgId}
                                    orgName={orgName}
                                    trigger={
                                        <Button variant="primary">
                                            <Icon name="plus" />
                                            {NEW_APP_COPY}
                                        </Button>
                                    }
                                />
                            )}
                        </>
                    }
                />
                <SectionGap>
                    <StatsRow orgId={orgId} />
                    <div className={styles.grid}>
                        <RecentApps orgId={orgId} orgName={orgName} />
                        <AttentionList orgId={orgId} />
                    </div>
                </SectionGap>
            </Page>
        </InvitesProvider>
    );
}

function OrganizationSummary({ orgId }: Readonly<{ orgId: OrgId }>) {
    const organization = useOrganization(orgId).data;
    if (organization === undefined) return null;
    const { lead, address, created } = organizationSummaryCopy(organization);

    return (
        <>
            {lead} <span className={styles.address}>{address}</span>. {created}
        </>
    );
}
