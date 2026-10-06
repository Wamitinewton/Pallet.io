import type { OrgId, TeamId } from "@/shared/domain/ids";
import { queryScopes } from "@/shared/presentation/query";
import { queryOptions } from "@tanstack/react-query";
import type { GetTeam } from "../application/get-team";
import type { ListTeamMembers } from "../application/list-team-members";
import type { ListTeams } from "../application/list-teams";
import { TEAM_ROSTER_QUERY, type TeamListQuery, type TeamMemberListQuery } from "../domain/team-list-query";

export const teamKeys = {
    all: queryScopes.teams,
    /** Every page of the teams grid, so a change to one team's card reaches all of them. */
    lists: (orgId: OrgId) => [...teamKeys.all(orgId), "list"] as const,
    list: (orgId: OrgId, query: TeamListQuery) => [...teamKeys.lists(orgId), query] as const,
    /** Everything about one team: dropped whole when the team is deleted. */
    team: (orgId: OrgId, teamId: TeamId) => [...teamKeys.all(orgId), "team", teamId] as const,
    detail: (orgId: OrgId, teamId: TeamId) => [...teamKeys.team(orgId, teamId), "detail"] as const,
    /** Every page of the team's people, and the roster a picker reads. */
    memberLists: (orgId: OrgId, teamId: TeamId) => [...teamKeys.team(orgId, teamId), "members"] as const,
    memberList: (orgId: OrgId, teamId: TeamId, query: TeamMemberListQuery) =>
        [...teamKeys.memberLists(orgId, teamId), query] as const,
};

export const teamQueries = {
    list: (listTeams: ListTeams, orgId: OrgId, query: TeamListQuery) =>
        queryOptions({
            queryKey: teamKeys.list(orgId, query),
            queryFn: () => listTeams(orgId, query),
        }),
    detail: (getTeam: GetTeam, orgId: OrgId, teamId: TeamId) =>
        queryOptions({
            queryKey: teamKeys.detail(orgId, teamId),
            queryFn: () => getTeam(orgId, teamId),
        }),
    members: (listTeamMembers: ListTeamMembers, orgId: OrgId, teamId: TeamId, query: TeamMemberListQuery) =>
        queryOptions({
            queryKey: teamKeys.memberList(orgId, teamId, query),
            queryFn: () => listTeamMembers(orgId, teamId, query),
        }),
    roster: (listTeamMembers: ListTeamMembers, orgId: OrgId, teamId: TeamId) =>
        teamQueries.members(listTeamMembers, orgId, teamId, TEAM_ROSTER_QUERY),
};
