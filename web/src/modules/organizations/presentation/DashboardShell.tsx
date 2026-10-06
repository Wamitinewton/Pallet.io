"use client";

import { AccountMenu, accountPaths } from "@/modules/identity";
import { NotificationBell, notificationPaths, useUnreadCount } from "@/modules/notifications";
import type { OrgId } from "@/shared/domain/ids";
import { AppShell, Nav, NavGroup, NavLink, Sidebar } from "@/shared/presentation/shell";
import type { Route } from "next";
import { useParams } from "next/navigation";
import type { ReactNode } from "react";
import { resolveShellOrganization } from "../application/resolve-shell-organization";
import { organizationPath } from "../domain/org-paths";
import { OrgNavigation } from "./OrgNavigation";
import { OrgSwitcher } from "./OrgSwitcher";
import { useMyOrganizations } from "./use-organizations";

const LANDING_PATH = "/orgs" as Route;

export interface DashboardShellProps {
    /** The sidebar to show on pages outside an organization, such as the account page. */
    readonly fallbackOrgId: OrgId | undefined;
    readonly children: ReactNode;
}

export function DashboardShell({ fallbackOrgId, children }: DashboardShellProps) {
    const { orgId: urlOrgId } = useParams<{ orgId?: string }>();
    const organizations = useMyOrganizations();
    const orgId = resolveShellOrganization(urlOrgId, organizations.data, fallbackOrgId);

    return (
        <AppShell
            sidebar={
                <Sidebar
                    homeHref={orgId === undefined ? LANDING_PATH : (organizationPath(orgId) as Route)}
                    switcher={<OrgSwitcher activeOrgId={orgId} />}
                    navigation={orgId !== undefined && <OrgNavigation orgId={orgId} />}
                    footer={<AccountNavigation />}
                />
            }
            actions={
                <>
                    <NotificationBell />
                    <AccountMenu />
                </>
            }
        >
            {children}
        </AppShell>
    );
}

function AccountNavigation() {
    const unread = useUnreadCount();
    return (
        <Nav label="Account">
            <NavGroup>
                <NavLink href={notificationPaths.list} icon="bell" count={unread === 0 ? undefined : unread}>
                    Notifications
                </NavLink>
                <NavLink href={accountPaths.account} icon="user">
                    Your account
                </NavLink>
            </NavGroup>
        </Nav>
    );
}
