"use client";

import type { OrgId } from "@/shared/domain/ids";
import { useQuery } from "@tanstack/react-query";
import { useOrganizationUseCases } from "./organization-use-cases";
import { organizationQueries } from "./queries";

export function useMyOrganizations() {
    const { listMyOrganizations } = useOrganizationUseCases();
    return useQuery(organizationQueries.mine(listMyOrganizations));
}

/** The organization with its counts; the nav reads its counts from here, so changing a count invalidates it. */
export function useOrganization(orgId: OrgId) {
    const { getOrganization } = useOrganizationUseCases();
    return useQuery(organizationQueries.detail(getOrganization, orgId));
}
