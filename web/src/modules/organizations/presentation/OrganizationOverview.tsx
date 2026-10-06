"use client";

import type { OrgId } from "@/shared/domain/ids";
import { PageBreadcrumbs } from "@/shared/presentation/shell";
import { Page, PageHead } from "@/shared/presentation/ui";
import { useSuspenseQuery } from "@tanstack/react-query";
import type { Route } from "next";
import { organizationPath } from "../domain/org-paths";
import { KIND_LABEL } from "./organization-copy";
import { useOrganizationUseCases } from "./organization-use-cases";
import { organizationQueries } from "./queries";

/** Holds the overview route until its content arrives with the overview screen. */
export function OrganizationOverview({ orgId }: Readonly<{ orgId: OrgId }>) {
    const { getOrganization } = useOrganizationUseCases();
    const { data: organization } = useSuspenseQuery(organizationQueries.detail(getOrganization, orgId));

    return (
        <Page>
            <PageBreadcrumbs
                items={[{ label: organization.name, href: organizationPath(orgId) as Route }, { label: "Overview" }]}
            />
            <PageHead title={organization.name} description={`${KIND_LABEL[organization.kind]} organization`} />
        </Page>
    );
}
