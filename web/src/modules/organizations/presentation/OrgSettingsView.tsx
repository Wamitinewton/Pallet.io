"use client";

import { useOrgAccess } from "@/modules/members";
import type { OrgId } from "@/shared/domain/ids";
import { PageBreadcrumbs } from "@/shared/presentation/shell";
import { PageHead, PageNarrow, SectionGap } from "@/shared/presentation/ui";
import { useSuspenseQuery } from "@tanstack/react-query";
import type { Route } from "next";
import { organizationPath } from "../domain/org-paths";
import { DeleteOrganizationPanel } from "./DeleteOrganizationPanel";
import { MyOrganizationsPanel } from "./MyOrganizationsPanel";
import { SETTINGS_DESCRIPTION_COPY, SETTINGS_TITLE_COPY } from "./organization-copy";
import { useOrganizationUseCases } from "./organization-use-cases";
import { OrgDetailsPanel } from "./OrgDetailsPanel";
import { organizationQueries } from "./queries";
import { RenameOrgPanel } from "./RenameOrgPanel";

export function OrgSettingsView({ orgId }: Readonly<{ orgId: OrgId }>) {
    const { getOrganization } = useOrganizationUseCases();
    const { data: organization } = useSuspenseQuery(organizationQueries.detail(getOrganization, orgId));
    const access = useOrgAccess();

    return (
        <PageNarrow>
            <PageBreadcrumbs
                items={[{ label: organization.name, href: organizationPath(orgId) as Route }, { label: "Settings" }]}
            />
            <PageHead title={SETTINGS_TITLE_COPY} description={SETTINGS_DESCRIPTION_COPY} />
            <SectionGap>
                <RenameOrgPanel organization={organization} canRename={access.can("org.rename")} />
                <OrgDetailsPanel organization={organization} />
                <MyOrganizationsPanel currentOrgId={orgId} />
                {access.can("org.delete") && <DeleteOrganizationPanel organization={organization} />}
            </SectionGap>
        </PageNarrow>
    );
}
