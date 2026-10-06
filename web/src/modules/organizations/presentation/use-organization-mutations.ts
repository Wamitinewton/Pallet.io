"use client";

import { useStepUp } from "@/modules/session";
import { useMutation, useQueryClient, type UseMutationResult } from "@tanstack/react-query";
import type { NewOrganizationForm, Organization, RenameOrganizationForm } from "../domain/organization";
import { forgetLastOrganization } from "./last-organization";
import { useOrganizationUseCases } from "./organization-use-cases";
import { organizationKeys } from "./queries";

/** Settles once my organizations are read again, so the switcher already lists the new one on arrival. */
export function useCreateOrganization(): UseMutationResult<Organization, unknown, NewOrganizationForm> {
    const { createOrganization } = useOrganizationUseCases();
    const queryClient = useQueryClient();

    return useMutation({
        mutationFn: createOrganization,
        onSuccess: async (organization) => {
            queryClient.setQueryData(organizationKeys.detail(organization.orgId), organization);
            await queryClient.invalidateQueries({ queryKey: organizationKeys.mine() });
        },
    });
}

export function useRenameOrganization(
    orgId: Organization["orgId"],
): UseMutationResult<Organization, unknown, RenameOrganizationForm> {
    const { renameOrganization } = useOrganizationUseCases();
    const queryClient = useQueryClient();

    return useMutation({
        mutationFn: (rename) => renameOrganization(orgId, rename),
        onSuccess: (organization) => {
            queryClient.setQueryData(organizationKeys.detail(orgId), organization);
            void queryClient.invalidateQueries({ queryKey: organizationKeys.mine() });
        },
    });
}

/**
 * Runs through step-up. A deleted organization's cache is dropped, not invalidated: nothing under it can
 * ever be read again.
 */
export function useDeleteOrganization({
    orgId,
    slug,
}: Pick<Organization, "orgId" | "slug">): UseMutationResult<void, unknown, string> {
    const { deleteOrganization } = useOrganizationUseCases();
    const { runWithStepUp } = useStepUp();
    const queryClient = useQueryClient();

    return useMutation({
        mutationFn: (confirmation) => runWithStepUp(() => deleteOrganization({ orgId, slug, confirmation })),
        onSuccess: async () => {
            const scope = organizationKeys.scope(orgId);
            await queryClient.cancelQueries({ queryKey: scope });
            queryClient.removeQueries({ queryKey: scope });
            forgetLastOrganization(orgId);
            void queryClient.invalidateQueries({ queryKey: organizationKeys.mine() });
        },
    });
}
