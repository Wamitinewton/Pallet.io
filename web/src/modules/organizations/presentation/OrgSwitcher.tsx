"use client";

import type { OrgId } from "@/shared/domain/ids";
import { ROLE_LABEL } from "@/shared/presentation/roles";
import {
    Icon,
    Menu,
    MenuContent,
    MenuItem,
    MenuLabel,
    MenuSeparator,
    MenuTrigger,
    OrgAvatar,
} from "@/shared/presentation/ui";
import type { Route } from "next";
import { usePathname, useRouter } from "next/navigation";
import { useState } from "react";
import { switchOrganizationPath } from "../domain/org-paths";
import type { OrgSummary } from "../domain/organization";
import { NewOrganizationDialog } from "./NewOrganizationDialog";
import { KIND_LABEL } from "./organization-copy";
import styles from "./OrgSwitcher.module.css";
import { useMyOrganizations } from "./use-organizations";

function summary(org: OrgSummary): string {
    return `${KIND_LABEL[org.kind]} · ${ROLE_LABEL[org.myRole]}`;
}

function roleSentence(org: OrgSummary): string {
    return `${KIND_LABEL[org.kind]} · you're the ${ROLE_LABEL[org.myRole].toLowerCase()}`;
}

export interface OrgSwitcherProps {
    readonly activeOrgId: OrgId | undefined;
}

export function OrgSwitcher({ activeOrgId }: OrgSwitcherProps) {
    const organizations = useMyOrganizations();
    const router = useRouter();
    const pathname = usePathname();
    const [creating, setCreating] = useState(false);
    const items = organizations.data?.items ?? [];
    const active = items.find((org) => org.orgId === activeOrgId);

    const open = (org: OrgSummary) => {
        if (org.orgId !== activeOrgId) router.push(switchOrganizationPath(pathname, org.orgId) as Route);
    };

    return (
        <>
            <Menu>
                <MenuTrigger>
                    <button
                        type="button"
                        className={styles.trigger}
                        {...(active !== undefined && { "aria-label": `${active.name}, ${roleSentence(active)}` })}
                    >
                        {active === undefined ? (
                            <span className={styles.placeholder} aria-hidden="true">
                                <Icon name="building" />
                            </span>
                        ) : (
                            <OrgAvatar name={active.name} className={styles.avatar} />
                        )}
                        <span className={styles.text}>
                            <span className={styles.name}>{active?.name ?? "Your organizations"}</span>
                            {active !== undefined && <span className={styles.meta}>{roleSentence(active)}</span>}
                        </span>
                        <Icon name="selector" className={styles.selector} />
                    </button>
                </MenuTrigger>
                <MenuContent
                    align="start"
                    className={styles.menu}
                    onCloseAutoFocus={(event) => {
                        if (creating) event.preventDefault();
                    }}
                >
                    <MenuLabel>Your organizations</MenuLabel>
                    {organizations.isPending && <MenuItem disabled>Loading organizations…</MenuItem>}
                    {organizations.isError && (
                        <MenuItem
                            onSelect={() => {
                                void organizations.refetch();
                            }}
                        >
                            <Icon name="refresh" />
                            Couldn&apos;t load them. Try again
                        </MenuItem>
                    )}
                    {items.map((org) => (
                        <MenuItem
                            key={org.orgId}
                            className={styles.org}
                            aria-label={`${org.name}, ${summary(org)}${org.orgId === activeOrgId ? ", open" : ""}`}
                            onSelect={() => {
                                open(org);
                            }}
                        >
                            <OrgAvatar name={org.name} size="sm" />
                            <span className={styles.text}>
                                {org.name}
                                <span className={styles.meta}>{summary(org)}</span>
                            </span>
                            {org.orgId === activeOrgId && <Icon name="check" className={styles.check} />}
                        </MenuItem>
                    ))}
                    <MenuSeparator />
                    <MenuItem
                        onSelect={() => {
                            setCreating(true);
                        }}
                    >
                        <Icon name="plus" />
                        New organization
                    </MenuItem>
                </MenuContent>
            </Menu>
            <NewOrganizationDialog open={creating} onOpenChange={setCreating} />
        </>
    );
}
