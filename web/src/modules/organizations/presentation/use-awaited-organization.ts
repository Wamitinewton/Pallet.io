"use client";

import type { OrgId } from "@/shared/domain/ids";
import { useHasPassed } from "@/shared/presentation/hooks";
import { useClock } from "@/shared/presentation/providers";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import type { OrgSummary } from "../domain/organization";
import { useOrganizationUseCases } from "./organization-use-cases";
import { PROVISIONING_PATIENCE_MS, PROVISIONING_POLL_MS } from "./ProvisioningState";
import { organizationQueries } from "./queries";

export interface AwaitedOrganization {
    /** The organization once the caller's membership in it has been projected. */
    readonly organization: OrgSummary | undefined;
    readonly gaveUp: boolean;
    readonly retry: () => void;
}

/** Polls my organizations until `orgId` is among them, since a new membership is projected asynchronously. */
export function useAwaitedOrganization(orgId: OrgId): AwaitedOrganization {
    const clock = useClock();
    const { listMyOrganizations } = useOrganizationUseCases();
    const [deadline, setDeadline] = useState(() => new Date(clock.now().getTime() + PROVISIONING_PATIENCE_MS));
    const gaveUp = useHasPassed(deadline);
    const organizations = useQuery({
        ...organizationQueries.mine(listMyOrganizations),
        refetchInterval: (query) =>
            gaveUp || query.state.data?.items.some((candidate) => candidate.orgId === orgId) === true
                ? false
                : PROVISIONING_POLL_MS,
    });
    const organization = organizations.data?.items.find((candidate) => candidate.orgId === orgId);

    return {
        organization,
        gaveUp: gaveUp && organization === undefined,
        retry: () => {
            setDeadline(new Date(clock.now().getTime() + PROVISIONING_PATIENCE_MS));
            void organizations.refetch();
        },
    };
}
