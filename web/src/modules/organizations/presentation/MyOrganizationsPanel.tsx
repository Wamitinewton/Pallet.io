"use client";

import type { OrgId } from "@/shared/domain/ids";
import { ErrorState } from "@/shared/presentation/errors";
import { RoleTag } from "@/shared/presentation/roles";
import {
    Badge,
    Button,
    Icon,
    List,
    ListItem,
    OrgAvatar,
    Panel,
    PanelFoot,
    PanelHead,
    Person,
    Skeleton,
    Text,
} from "@/shared/presentation/ui";
import { useInfiniteQuery } from "@tanstack/react-query";
import type { Route } from "next";
import Link from "next/link";
import { useId } from "react";
import { organizationPath } from "../domain/org-paths";
import type { OrgSummary } from "../domain/organization";
import { NewOrganizationDialog } from "./NewOrganizationDialog";
import { KIND_LABEL, MY_ORGANIZATIONS_DESCRIPTION_COPY } from "./organization-copy";
import { useOrganizationUseCases } from "./organization-use-cases";
import styles from "./OrgSettingsView.module.css";
import { organizationQueries } from "./queries";

const LOADING_ROWS = 2;

export interface MyOrganizationsPanelProps {
    readonly currentOrgId: OrgId;
}

export function MyOrganizationsPanel({ currentOrgId }: MyOrganizationsPanelProps) {
    const { listMyOrganizations } = useOrganizationUseCases();
    const organizations = useInfiniteQuery(organizationQueries.minePages(listMyOrganizations));
    const headingId = useId();
    const items = organizations.data?.pages.flatMap((page) => page.items) ?? [];

    const body = () => {
        if (organizations.isError && items.length === 0) {
            return (
                <ErrorState
                    error={organizations.error}
                    headingLevel={3}
                    retrying={organizations.isFetching}
                    onRetry={() => void organizations.refetch()}
                />
            );
        }
        if (organizations.isPending) return <LoadingRows />;
        return (
            <List aria-labelledby={headingId}>
                {items.map((org) => (
                    <OrganizationRow key={org.orgId} org={org} current={org.orgId === currentOrgId} />
                ))}
            </List>
        );
    };

    return (
        <Panel aria-labelledby={headingId}>
            <PanelHead
                title={<span id={headingId}>Your organizations</span>}
                description={MY_ORGANIZATIONS_DESCRIPTION_COPY}
            >
                <NewOrganizationDialog
                    trigger={
                        <Button variant="secondary" size="sm">
                            <Icon name="plus" />
                            New organization
                        </Button>
                    }
                />
            </PanelHead>
            {body()}
            {organizations.hasNextPage && (
                <PanelFoot className={styles.footCenter}>
                    <Button
                        variant="secondary"
                        size="sm"
                        loading={organizations.isFetchingNextPage}
                        onClick={() => void organizations.fetchNextPage()}
                    >
                        Load more
                    </Button>
                </PanelFoot>
            )}
        </Panel>
    );
}

function OrganizationRow({ org, current }: Readonly<{ org: OrgSummary; current: boolean }>) {
    return (
        <ListItem className={styles.organization}>
            <Link
                href={organizationPath(org.orgId) as Route}
                className={styles.organizationLink}
                {...(current && { "aria-current": "page" })}
            >
                <Person
                    avatar={<OrgAvatar name={org.name} />}
                    name={org.name}
                    meta={
                        <>
                            {org.kind === "PERSONAL" && `${KIND_LABEL.PERSONAL} · `}
                            <Text mono>{org.slug}</Text>
                        </>
                    }
                />
            </Link>
            {current && <Badge tone="blue">Current</Badge>}
            <RoleTag role={org.myRole} />
        </ListItem>
    );
}

function LoadingRows() {
    return (
        <List aria-busy="true" aria-label="Loading your organizations">
            {Array.from({ length: LOADING_ROWS }, (_, index) => (
                <ListItem key={index}>
                    <Skeleton width={36} height={36} />
                    <div className={styles.loadingText}>
                        <Skeleton width="30%" height={14} />
                        <Skeleton width="20%" height={12} style={{ marginTop: 6 }} />
                    </div>
                </ListItem>
            ))}
        </List>
    );
}
