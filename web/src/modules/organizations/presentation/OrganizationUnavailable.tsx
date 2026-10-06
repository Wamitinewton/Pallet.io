"use client";

import { ROLE_LABEL } from "@/shared/presentation/roles";
import { PageBreadcrumbs } from "@/shared/presentation/shell";
import { EmptyState, List, ListItem, OrgAvatar, Page, Panel, PanelHead, Person } from "@/shared/presentation/ui";
import type { Route } from "next";
import Link from "next/link";
import { organizationPath } from "../domain/org-paths";
import { KIND_LABEL } from "./organization-copy";
import styles from "./OrganizationUnavailable.module.css";
import { useMyOrganizations } from "./use-organizations";

/** Deliberately the same whether the organization is missing or simply not this account's. */
export function OrganizationUnavailable() {
    const organizations = useMyOrganizations().data?.items ?? [];

    return (
        <Page narrow>
            <PageBreadcrumbs items={[{ label: "Organization unavailable" }]} />
            <EmptyState
                icon="building"
                title="This organization isn't available"
                description="It doesn't exist, or this account isn't a member of it. Ask one of its owners for an invite, or open one of your organizations."
            />
            {organizations.length > 0 && (
                <Panel>
                    <PanelHead title="Your organizations" />
                    <List>
                        {organizations.map((org) => (
                            <ListItem key={org.orgId} className={styles.item}>
                                <Link href={organizationPath(org.orgId) as Route} className={styles.link}>
                                    <Person
                                        avatar={<OrgAvatar name={org.name} />}
                                        name={org.name}
                                        meta={`${KIND_LABEL[org.kind]} · ${ROLE_LABEL[org.myRole]}`}
                                    />
                                </Link>
                            </ListItem>
                        ))}
                    </List>
                </Panel>
            )}
        </Page>
    );
}
