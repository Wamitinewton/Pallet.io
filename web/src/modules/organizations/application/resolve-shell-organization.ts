import { asOrgId, type OrgId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import type { OrgSummary } from "../domain/organization";

/**
 * The organization whose sidebar the shell shows: the one in the URL unless my list proves it isn't mine
 * (then the landing one, so the sidebar never points into an organization this account can't open).
 */
export function resolveShellOrganization(
    urlOrgId: string | undefined,
    mine: Page<OrgSummary> | undefined,
    fallback: OrgId | undefined,
): OrgId | undefined {
    if (urlOrgId === undefined) return fallback;
    if (!mine?.isLast) return asOrgId(urlOrgId);
    return mine.items.some((org) => org.orgId === urlOrgId) ? asOrgId(urlOrgId) : fallback;
}
