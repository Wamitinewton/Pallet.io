import type { OrgId, TeamId, UserId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import type { NewTeam, Team, TeamMember, TeamRename } from "../domain/team";
import type { TeamListQuery, TeamMemberListQuery } from "../domain/team-list-query";

export interface TeamRepository {
    list(orgId: OrgId, query: TeamListQuery): Promise<Page<Team>>;
    get(orgId: OrgId, teamId: TeamId): Promise<Team>;
    create(orgId: OrgId, team: NewTeam): Promise<Team>;
    rename(orgId: OrgId, teamId: TeamId, rename: TeamRename): Promise<Team>;
    /** Detaches the team's apps; its people stay in the organization. */
    delete(orgId: OrgId, teamId: TeamId): Promise<void>;
    listMembers(orgId: OrgId, teamId: TeamId, query: TeamMemberListQuery): Promise<Page<TeamMember>>;
    addMember(orgId: OrgId, teamId: TeamId, userId: UserId): Promise<TeamMember>;
    removeMember(orgId: OrgId, teamId: TeamId, userId: UserId): Promise<void>;
}
