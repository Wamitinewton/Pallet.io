import { OrganizationOverview, orgIdFromParam } from "@/modules/organizations";
import type { Metadata } from "next";

export const metadata: Metadata = {
    title: "Overview",
};

export default async function OrganizationOverviewPage({ params }: PageProps<"/orgs/[orgId]">) {
    return <OrganizationOverview orgId={orgIdFromParam((await params).orgId)} />;
}
