"use client";

import { organizationPath, useAwaitedOrganization } from "@/modules/organizations";
import { DEFAULT_SIGNED_IN_PATH } from "@/modules/session";
import type { OrgId } from "@/shared/domain/ids";
import { Button, Callout, Icon, Row, Spinner, Stack } from "@/shared/presentation/ui";
import type { Route } from "next";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { joiningCopy, joiningSlowCopy, OPEN_PALLET_COPY, TRY_AGAIN_COPY } from "./acceptance-copy";

export interface JoiningOrganizationProps {
    readonly orgId: OrgId;
    readonly orgName: string;
}

/** The invite is accepted; the membership is projected moments later, and the organization opens once it is. */
export function JoiningOrganization({ orgId, orgName }: JoiningOrganizationProps) {
    const router = useRouter();
    const { organization, gaveUp, retry } = useAwaitedOrganization(orgId);

    useEffect(() => {
        if (organization !== undefined) router.replace(organizationPath(organization.orgId) as Route);
    }, [organization, router]);

    if (gaveUp) {
        return (
            <Callout tone="amber" icon="clock" role="alert">
                <Stack gap="sm">
                    <span>{joiningSlowCopy(orgName)}</span>
                    <Row>
                        <Button variant="secondary" size="sm" onClick={retry}>
                            <Icon name="refresh" />
                            {TRY_AGAIN_COPY}
                        </Button>
                        <Button asChild variant="ghost" size="sm">
                            <Link href={DEFAULT_SIGNED_IN_PATH}>{OPEN_PALLET_COPY}</Link>
                        </Button>
                    </Row>
                </Stack>
            </Callout>
        );
    }

    return (
        <Callout tone="blue" role="status">
            <Row>
                <Spinner />
                <span>{joiningCopy(orgName)}</span>
            </Row>
        </Callout>
    );
}
