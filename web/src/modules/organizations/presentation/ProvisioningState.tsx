"use client";

import { useHasPassed } from "@/shared/presentation/hooks";
import { useClock } from "@/shared/presentation/providers";
import { Button, EmptyState, Icon, Page, Spinner } from "@/shared/presentation/ui";
import { useQuery } from "@tanstack/react-query";
import type { Route } from "next";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { chooseLandingOrganization } from "../application/choose-landing-organization";
import { organizationPath } from "../domain/org-paths";
import { useOrganizationUseCases } from "./organization-use-cases";
import { organizationQueries } from "./queries";

export const PROVISIONING_POLL_MS = 2_000;
export const PROVISIONING_PATIENCE_MS = 30_000;

export interface ProvisioningStateProps {
    readonly lastOrgId: string | undefined;
}

/**
 * Right after sign-up, identity has provisioned the organization but org-team-service hasn't projected it
 * yet, so the list is briefly empty: wait for it rather than treat it as an error.
 */
export function ProvisioningState({ lastOrgId }: ProvisioningStateProps) {
    const clock = useClock();
    const router = useRouter();
    const { listMyOrganizations } = useOrganizationUseCases();
    const [deadline, setDeadline] = useState(() => new Date(clock.now().getTime() + PROVISIONING_PATIENCE_MS));
    const gaveUp = useHasPassed(deadline);
    const organizations = useQuery({
        ...organizationQueries.mine(listMyOrganizations),
        refetchInterval: gaveUp ? false : PROVISIONING_POLL_MS,
    });
    const landing = organizations.data && chooseLandingOrganization(organizations.data.items, lastOrgId);

    useEffect(() => {
        if (landing !== undefined) router.replace(organizationPath(landing) as Route);
    }, [landing, router]);

    if (gaveUp && landing === undefined) {
        return (
            <Page>
                <EmptyState
                    role="alert"
                    icon="clock"
                    title="This is taking longer than usual"
                    description="Your organization is still being set up. Give it a moment, then try again."
                    actions={
                        <Button
                            variant="secondary"
                            onClick={() => {
                                setDeadline(new Date(clock.now().getTime() + PROVISIONING_PATIENCE_MS));
                                void organizations.refetch();
                            }}
                        >
                            <Icon name="refresh" />
                            Try again
                        </Button>
                    }
                />
            </Page>
        );
    }

    return (
        <Page>
            <EmptyState
                role="status"
                icon="building"
                title="Setting up your organization"
                description="This usually takes a few seconds. You'll be taken there as soon as it's ready."
                actions={<Spinner />}
            />
        </Page>
    );
}
