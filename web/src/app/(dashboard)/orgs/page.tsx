import { getServerUseCases, lastOrganizationId, rethrowServerFailure } from "@/composition/server";
import {
    chooseLandingOrganization,
    organizationPath,
    organizationQueries,
    ProvisioningState,
} from "@/modules/organizations";
import { getServerQueryClient } from "@/shared/presentation/query/get-server-query-client";
import type { Metadata, Route } from "next";
import { redirect } from "next/navigation";

export const metadata: Metadata = {
    title: "Organizations",
};

export default async function OrganizationsPage() {
    const { organizations } = getServerUseCases();
    const [lastOrgId, mine] = await Promise.all([
        lastOrganizationId(),
        getServerQueryClient()
            .query(organizationQueries.mine(organizations.listMyOrganizations))
            .catch(rethrowServerFailure),
    ]);

    const landing = chooseLandingOrganization(mine.items, lastOrgId);
    if (landing !== undefined) redirect(organizationPath(landing) as Route);

    return <ProvisioningState lastOrgId={lastOrgId} />;
}
