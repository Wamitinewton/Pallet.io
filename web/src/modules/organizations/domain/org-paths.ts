import { asOrgId, type OrgId } from "@/shared/domain/ids";

export const ORG_SECTIONS = ["apps", "members", "teams", "github", "settings"] as const;

export type OrgSection = (typeof ORG_SECTIONS)[number];

const ORG_PATH = /^\/orgs\/[^/]+(?:\/([^/?#]+))?/;

function isOrgSection(value: string | undefined): value is OrgSection {
    return (ORG_SECTIONS as readonly (string | undefined)[]).includes(value);
}

export function organizationPath(orgId: string, section?: OrgSection): string {
    const base = `/orgs/${encodeURIComponent(orgId)}`;
    return section === undefined ? base : `${base}/${section}`;
}

export function sectionOf(pathname: string): OrgSection | undefined {
    const section = ORG_PATH.exec(pathname)?.[1];
    return isOrgSection(section) ? section : undefined;
}

/** The same section in another organization, at its list: a detail page's id means nothing there. */
export function switchOrganizationPath(pathname: string, orgId: string): string {
    return organizationPath(orgId, sectionOf(pathname));
}

/** The `[orgId]` route segment: an id the URL names, not yet one the caller is known to belong to. */
export function orgIdFromParam(value: string): OrgId {
    return asOrgId(value);
}
