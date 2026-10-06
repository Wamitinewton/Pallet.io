"use client";

import { ApiError } from "@/shared/domain/errors";
import type { OrgId, TeamId, UserId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import { messageFor } from "@/shared/presentation/errors";
import { queryScopes } from "@/shared/presentation/query";
import { useToast } from "@/shared/presentation/ui";
import {
    useMutation,
    useQueryClient,
    type QueryClient,
    type QueryKey,
    type UseMutationResult,
} from "@tanstack/react-query";
import { useEffect, useState } from "react";
import type { AddTeamMemberOutcome } from "../application/add-team-member";
import type { RemoveTeamMemberOutcome } from "../application/remove-team-member";
import {
    withMemberCountChange,
    type NewTeamForm,
    type RenameTeamForm,
    type Team,
    type TeamMember,
} from "../domain/team";
import { MEMBER_NOT_FOUND } from "../domain/team-errors";
import { teamKeys } from "./queries";
import { alreadyRemovedCopy, notRemovedCopy, removedCopy } from "./team-copy";
import { useTeamUseCases } from "./team-use-cases";

type Snapshot = readonly (readonly [QueryKey, unknown])[];

function mapTeams(page: Page<Team> | undefined, change: (team: Team) => Team | undefined): Page<Team> | undefined {
    if (page === undefined) return undefined;
    const items = page.items.flatMap((team) => {
        const changed = change(team);
        return changed === undefined ? [] : [changed];
    });
    return { ...page, items, totalItems: page.totalItems - (page.items.length - items.length) };
}

/** Moves one team's count on its page and on every grid page that shows its card, without reading either again. */
function changeMemberCount(queryClient: QueryClient, orgId: OrgId, teamId: TeamId, change: number): void {
    queryClient.setQueryData<Team>(teamKeys.detail(orgId, teamId), (team) =>
        team === undefined ? undefined : withMemberCountChange(team, change),
    );
    queryClient.setQueriesData<Page<Team>>({ queryKey: teamKeys.lists(orgId) }, (page) =>
        mapTeams(page, (team) => (team.id === teamId ? withMemberCountChange(team, change) : team)),
    );
}

function withoutMember(page: Page<TeamMember> | undefined, userId: UserId): Page<TeamMember> | undefined {
    if (page === undefined) return undefined;
    const items = page.items.filter((member) => member.userId !== userId);
    return { ...page, items, totalItems: page.totalItems - (page.items.length - items.length) };
}

function restore(queryClient: QueryClient, snapshot: Snapshot | undefined): void {
    for (const [key, data] of snapshot ?? []) queryClient.setQueryData(key, data);
}

/** The new team is seeded so its page opens without a read; the grid and the organization's count follow. */
export function useCreateTeam(orgId: OrgId): UseMutationResult<Team, unknown, NewTeamForm> {
    const { createTeam } = useTeamUseCases();
    const queryClient = useQueryClient();

    return useMutation({
        mutationFn: (team) => createTeam(orgId, team),
        onSuccess: (team) => {
            queryClient.setQueryData(teamKeys.detail(orgId, team.id), team);
            void queryClient.invalidateQueries({ queryKey: teamKeys.lists(orgId) });
            void queryClient.invalidateQueries({ queryKey: queryScopes.organization(orgId) });
        },
    });
}

/** The grid is read again, since a new name can move the card. */
export function useRenameTeam(orgId: OrgId, team: Team): UseMutationResult<Team, unknown, RenameTeamForm> {
    const { renameTeam } = useTeamUseCases();
    const queryClient = useQueryClient();

    return useMutation({
        mutationFn: (rename) => renameTeam({ orgId, team, rename }),
        onSuccess: (renamed) => {
            queryClient.setQueryData(teamKeys.detail(orgId, renamed.id), renamed);
            queryClient.setQueriesData<Page<Team>>({ queryKey: teamKeys.lists(orgId) }, (page) =>
                mapTeams(page, (current) => (current.id === renamed.id ? renamed : current)),
            );
            void queryClient.invalidateQueries({ queryKey: teamKeys.lists(orgId) });
        },
    });
}

/**
 * The team's own queries are dropped once its page has gone, not while it is still on screen, where a
 * dropped query would be read again and answer `404`. Its apps lose their team, so the apps list is read
 * again with the grid and the organization's count.
 */
export function useDeleteTeam(orgId: OrgId, team: Team): UseMutationResult<void, unknown, string> {
    const { deleteTeam } = useTeamUseCases();
    const queryClient = useQueryClient();
    const [deleted, setDeleted] = useState(false);
    const teamId = team.id;

    useEffect(() => {
        if (!deleted) return;
        return () => {
            queryClient.removeQueries({ queryKey: teamKeys.team(orgId, teamId) });
        };
    }, [deleted, queryClient, orgId, teamId]);

    return useMutation({
        mutationFn: (confirmation) => deleteTeam({ orgId, team, confirmation }),
        onSuccess: async () => {
            setDeleted(true);
            await queryClient.cancelQueries({ queryKey: teamKeys.team(orgId, teamId) });
            queryClient.setQueriesData<Page<Team>>({ queryKey: teamKeys.lists(orgId) }, (page) =>
                mapTeams(page, (current) => (current.id === team.id ? undefined : current)),
            );
            void queryClient.invalidateQueries({ queryKey: teamKeys.lists(orgId) });
            void queryClient.invalidateQueries({ queryKey: queryScopes.organization(orgId) });
            void queryClient.invalidateQueries({ queryKey: queryScopes.apps(orgId) });
        },
    });
}

export interface TeamMemberCandidate {
    readonly userId: UserId;
    readonly displayName: string;
}

/**
 * A new member lands on the right page only once the backend sorts them in, so the team's people are read
 * again; the counts move straight away. When they were already on the team, everything about it is read
 * again quietly; when they have left the organization, so are the organization's members.
 */
export function useAddTeamMember(
    orgId: OrgId,
    teamId: TeamId,
): UseMutationResult<AddTeamMemberOutcome, unknown, TeamMemberCandidate> {
    const { addTeamMember } = useTeamUseCases();
    const queryClient = useQueryClient();

    return useMutation({
        mutationFn: ({ userId }) => addTeamMember(orgId, teamId, userId),
        onSuccess: (outcome) => {
            if (outcome.status === "alreadyInTeam") {
                void queryClient.invalidateQueries({ queryKey: teamKeys.team(orgId, teamId) });
                void queryClient.invalidateQueries({ queryKey: teamKeys.lists(orgId) });
                return;
            }
            changeMemberCount(queryClient, orgId, teamId, 1);
            void queryClient.invalidateQueries({ queryKey: teamKeys.memberLists(orgId, teamId) });
        },
        onError: (error) => {
            if (error instanceof ApiError && error.is(MEMBER_NOT_FOUND)) {
                void queryClient.invalidateQueries({ queryKey: queryScopes.members(orgId) });
            }
        },
    });
}

interface RemovalSnapshot {
    readonly snapshot: Snapshot;
}

/**
 * Takes the person off every cached page of the team at once and moves the counts with them. A refusal puts
 * everything back. The outcome is toasted from here, so it is reported after the confirmation has closed.
 */
export function useRemoveTeamMember(
    orgId: OrgId,
    team: Pick<Team, "id" | "name">,
): UseMutationResult<RemoveTeamMemberOutcome, unknown, TeamMember, RemovalSnapshot> {
    const { removeTeamMember } = useTeamUseCases();
    const queryClient = useQueryClient();
    const toast = useToast();
    const members = teamKeys.memberLists(orgId, team.id);

    return useMutation({
        mutationFn: (member) => removeTeamMember(orgId, team.id, member.userId),
        onMutate: async (member) => {
            await Promise.all([
                queryClient.cancelQueries({ queryKey: members }),
                queryClient.cancelQueries({ queryKey: teamKeys.detail(orgId, team.id) }),
            ]);
            const snapshot: Snapshot = [
                ...queryClient.getQueriesData({ queryKey: members }),
                ...queryClient.getQueriesData({ queryKey: teamKeys.detail(orgId, team.id) }),
                ...queryClient.getQueriesData({ queryKey: teamKeys.lists(orgId) }),
            ];
            queryClient.setQueriesData<Page<TeamMember>>({ queryKey: members }, (page) =>
                withoutMember(page, member.userId),
            );
            changeMemberCount(queryClient, orgId, team.id, -1);
            return { snapshot };
        },
        onSuccess: (outcome, member) => {
            if (outcome === "alreadyRemoved") {
                toast.info(alreadyRemovedCopy(member.displayName, team.name));
                void queryClient.invalidateQueries({ queryKey: teamKeys.detail(orgId, team.id) });
                void queryClient.invalidateQueries({ queryKey: teamKeys.lists(orgId) });
                return;
            }
            toast.success(removedCopy(member.displayName, team.name));
        },
        onError: (error, member, context) => {
            restore(queryClient, context?.snapshot);
            toast.error(messageFor(error, notRemovedCopy(member.displayName)));
        },
        onSettled: () => queryClient.invalidateQueries({ queryKey: members }),
    });
}
