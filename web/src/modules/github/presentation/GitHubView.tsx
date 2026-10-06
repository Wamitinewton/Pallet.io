"use client";

import { useOrgAccess } from "@/modules/members";
import type { OrgId } from "@/shared/domain/ids";
import { PageBreadcrumbs } from "@/shared/presentation/shell";
import { Button, Callout, Icon, Page, PageHead, SectionGap } from "@/shared/presentation/ui";
import Link from "next/link";
import { useQueryStates } from "nuqs";
import { useEffect, useState } from "react";
import type { GitHubReturnFlag } from "../domain/return-intent";
import { ConnectInstallationDialog, type ConnectStep } from "./ConnectInstallationDialog";
import {
    CONNECT_GITHUB_COPY,
    GITHUB_TITLE_COPY,
    githubDescriptionCopy,
    LINK_ONE_NOW_COPY,
    RETURNED_COPY,
} from "./github-copy";
import { appsPath, orgOverviewPath } from "./github-paths";
import { GitHubSessionPanel } from "./GitHubSessionPanel";
import { InstallationList } from "./InstallationList";
import { githubSearchParams } from "./search-params";

export interface GitHubViewProps {
    readonly orgId: OrgId;
    readonly orgName: string;
}

export function GitHubView({ orgId, orgName }: GitHubViewProps) {
    const canManage = useOrgAccess().can("github.installations.manage");
    const { returned, initialStep } = useArrival(canManage);
    const [step, setStep] = useState<ConnectStep | null>(initialStep);

    return (
        <Page>
            <PageBreadcrumbs items={[{ label: orgName, href: orgOverviewPath(orgId) }, { label: GITHUB_TITLE_COPY }]} />
            <PageHead
                title={GITHUB_TITLE_COPY}
                description={githubDescriptionCopy(orgName)}
                actions={
                    canManage && (
                        <ConnectInstallationDialog
                            orgId={orgId}
                            orgName={orgName}
                            step={step}
                            onStepChange={setStep}
                            trigger={
                                <Button variant="primary">
                                    <Icon name="github" />
                                    {CONNECT_GITHUB_COPY}
                                </Button>
                            }
                        />
                    )
                }
            />
            <SectionGap>
                {returned !== null && <ReturnedCallout orgId={orgId} flag={returned} />}
                <InstallationList
                    orgId={orgId}
                    orgName={orgName}
                    connectAction={
                        <Button
                            variant="secondary"
                            onClick={() => {
                                setStep("choose");
                            }}
                        >
                            <Icon name="github" />
                            {CONNECT_GITHUB_COPY}
                        </Button>
                    }
                />
                <GitHubSessionPanel orgId={orgId} />
            </SectionGap>
        </Page>
    );
}

/**
 * What the address said on arrival: back from GitHub, or asked to open the dialog on the existing
 * installation step. Both are read once and then taken off the address, so a reload shows neither again.
 */
function useArrival(canManage: boolean): { returned: GitHubReturnFlag | null; initialStep: ConnectStep | null } {
    const [params, setParams] = useQueryStates(githubSearchParams);
    const [arrival] = useState(() => ({
        returned: params.github,
        initialStep: canManage && params.connect === "existing" ? ("existing" as const) : null,
    }));

    useEffect(() => {
        if (params.github !== null || params.connect !== null) void setParams({ github: null, connect: null });
    }, [params.github, params.connect, setParams]);

    return arrival;
}

function ReturnedCallout({ orgId, flag }: Readonly<{ orgId: OrgId; flag: GitHubReturnFlag }>) {
    const { title, body } = RETURNED_COPY[flag];

    return (
        <Callout tone="green" role="status">
            <strong>{title}</strong> {body}{" "}
            {flag === "installed" && <Link href={appsPath(orgId)}>{LINK_ONE_NOW_COPY}</Link>}
        </Callout>
    );
}
