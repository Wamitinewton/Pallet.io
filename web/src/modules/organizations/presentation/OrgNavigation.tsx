"use client";

import type { OrgId } from "@/shared/domain/ids";
import { Nav, NavGroup, NavLink } from "@/shared/presentation/shell";
import type { Route } from "next";
import { organizationPath, type OrgSection } from "../domain/org-paths";
import { useOrganization } from "./use-organizations";

export function OrgNavigation({ orgId }: Readonly<{ orgId: OrgId }>) {
    const counts = useOrganization(orgId).data?.counts;
    const href = (section?: OrgSection) => organizationPath(orgId, section) as Route;

    return (
        <Nav label="Main">
            <NavGroup>
                <NavLink href={href()} icon="home" exact>
                    Overview
                </NavLink>
                <NavLink href={href("apps")} icon="apps" count={counts?.apps}>
                    Apps
                </NavLink>
            </NavGroup>
            <NavGroup label="Organization">
                <NavLink href={href("members")} icon="users" count={counts?.members}>
                    Members
                </NavLink>
                <NavLink href={href("teams")} icon="teams" count={counts?.teams}>
                    Teams
                </NavLink>
                <NavLink href={href("github")} icon="github">
                    GitHub
                </NavLink>
                <NavLink href={href("settings")} icon="settings">
                    Settings
                </NavLink>
            </NavGroup>
        </Nav>
    );
}
