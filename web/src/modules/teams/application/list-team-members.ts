import type { OrgId, TeamId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import type { TeamMember } from "../domain/team";
import type { TeamMemberListQuery } from "../domain/team-list-query";
import type { TeamRepository } from "./ports";

export type ListTeamMembers = (orgId: OrgId, teamId: TeamId, query: TeamMemberListQuery) => Promise<Page<TeamMember>>;

export function makeListTeamMembers(teams: TeamRepository): ListTeamMembers {
    return (orgId, teamId, query) => teams.listMembers(orgId, teamId, query);
}
