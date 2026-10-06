"use client";

import { useSessionSummary } from "@/modules/session";
import { Copyable, KeyValue, KeyValueRow, Panel, PanelBody, PanelHead, Text } from "@/shared/presentation/ui";
import type { Organization } from "../domain/organization";
import { KIND_DESCRIPTION, SLUG_FIXED_COPY, sizeCopy } from "./organization-copy";
import styles from "./OrgSettingsView.module.css";

const createdDate = new Intl.DateTimeFormat("en-GB", { dateStyle: "long", timeZone: "UTC" });

export interface OrgDetailsPanelProps {
    readonly organization: Organization;
}

/** The owner is named only when it is the caller; anyone else's name arrives with the members list. */
export function OrgDetailsPanel({ organization }: OrgDetailsPanelProps) {
    const me = useSessionSummary().data?.userId;

    return (
        <Panel>
            <PanelHead title="Details" />
            <PanelBody>
                <KeyValue>
                    <KeyValueRow term="Type">{KIND_DESCRIPTION[organization.kind]}</KeyValueRow>
                    {me !== undefined && me === organization.ownerUserId && <KeyValueRow term="Owner">You</KeyValueRow>}
                    <KeyValueRow term="Created">
                        <time dateTime={organization.createdAt}>
                            {createdDate.format(new Date(organization.createdAt))}
                        </time>
                    </KeyValueRow>
                    <KeyValueRow term="URL">
                        <span className={styles.slug}>
                            <Copyable value={organization.slug} label="organization URL" />
                            <Text tone="muted" size="sm">
                                {SLUG_FIXED_COPY}
                            </Text>
                        </span>
                    </KeyValueRow>
                    <KeyValueRow term="Organization ID">
                        <Copyable value={organization.orgId} label="organization ID" />
                    </KeyValueRow>
                    <KeyValueRow term="Size">
                        <Text numeric>{sizeCopy(organization.counts)}</Text>
                    </KeyValueRow>
                </KeyValue>
            </PanelBody>
        </Panel>
    );
}
