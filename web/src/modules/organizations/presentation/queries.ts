import type { OrgId } from "@/shared/domain/ids";
import { queryScopes } from "@/shared/presentation/query";
import { infiniteQueryOptions, queryOptions } from "@tanstack/react-query";
import type { GetOrganization } from "../application/get-organization";
import type { ListMyOrganizations } from "../application/list-my-organizations";

export const organizationKeys = {
    /** Every view of my organizations, so one invalidation refreshes the switcher and the settings list. */
    mine: queryScopes.myOrganizations,
    minePages: () => [...organizationKeys.mine(), "pages"] as const,
    /** Everything scoped to one organization: dropped whole when the organization is deleted. */
    scope: queryScopes.org,
    detail: queryScopes.organization,
};

export const organizationQueries = {
    mine: (listMyOrganizations: ListMyOrganizations) =>
        queryOptions({
            queryKey: organizationKeys.mine(),
            queryFn: () => listMyOrganizations(),
        }),
    minePages: (listMyOrganizations: ListMyOrganizations) =>
        infiniteQueryOptions({
            queryKey: organizationKeys.minePages(),
            queryFn: ({ pageParam }) => listMyOrganizations(pageParam),
            initialPageParam: 0,
            getNextPageParam: (last) => (last.isLast ? undefined : last.page + 1),
        }),
    detail: (getOrganization: GetOrganization, orgId: OrgId) =>
        queryOptions({
            queryKey: organizationKeys.detail(orgId),
            queryFn: () => getOrganization(orgId),
        }),
};
