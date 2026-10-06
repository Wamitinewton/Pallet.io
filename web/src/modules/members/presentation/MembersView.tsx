"use client";

import type { OrgId } from "@/shared/domain/ids";
import { PageBreadcrumbs } from "@/shared/presentation/shell";
import { Page, PageHead, Tabs, TabsContent, TabsList, TabsTrigger } from "@/shared/presentation/ui";
import type { Route } from "next";
import type { ReactNode } from "react";
import { MEMBERS_TITLE_COPY, membersDescription, TAB_LABEL, TABS_LABEL_COPY } from "./member-copy";
import { MemberList } from "./MemberList";
import styles from "./MembersView.module.css";
import { useOrgAccess } from "./OrgAccessProvider";
import { MEMBERS_PAGE_TABS, type MembersPageTab } from "./search-params";
import { useMembersTab } from "./use-members-tab";

/** The invites tab, which depends on this module and so is handed in by the page rather than imported. */
export interface MembersInvitesSlot {
    /** Shown beside the page title, on either tab. */
    readonly action: ReactNode;
    readonly panel: ReactNode;
}

export interface MembersViewProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    /** The organization's overview, for the breadcrumbs. */
    readonly orgHref: Route;
    /** Shown only to someone who can manage invites; anyone else sees the members list alone. */
    readonly invites?: MembersInvitesSlot;
}

function isMembersTab(value: string): value is MembersPageTab {
    return (MEMBERS_PAGE_TABS as readonly string[]).includes(value);
}

export function MembersView({ orgId, orgName, orgHref, invites }: MembersViewProps) {
    const access = useOrgAccess();
    const [tab, selectTab] = useMembersTab();
    const tabbed = invites !== undefined && access.can("invites.manage");
    const members = <MemberList orgId={orgId} orgName={orgName} />;

    return (
        <Page>
            <PageBreadcrumbs items={[{ label: orgName, href: orgHref }, { label: MEMBERS_TITLE_COPY }]} />
            <PageHead
                title={MEMBERS_TITLE_COPY}
                description={membersDescription(orgName)}
                actions={tabbed ? invites.action : undefined}
            />

            {tabbed ? (
                <Tabs
                    value={tab}
                    onValueChange={(value) => {
                        if (isMembersTab(value)) selectTab(value);
                    }}
                >
                    <TabsList aria-label={TABS_LABEL_COPY} className={styles.tabs}>
                        {MEMBERS_PAGE_TABS.map((value) => (
                            <TabsTrigger key={value} value={value}>
                                {TAB_LABEL[value]}
                            </TabsTrigger>
                        ))}
                    </TabsList>
                    <TabsContent value="members">{members}</TabsContent>
                    <TabsContent value="invites">{invites.panel}</TabsContent>
                </Tabs>
            ) : (
                members
            )}
        </Page>
    );
}
