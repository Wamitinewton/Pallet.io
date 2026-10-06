import type { OrgId, TeamId } from "@/shared/domain/ids";
import type { Team } from "../domain/team";
import type { TeamRepository } from "./ports";

export type GetTeam = (orgId: OrgId, teamId: TeamId) => Promise<Team>;

export function makeGetTeam(teams: TeamRepository): GetTeam {
    return (orgId, teamId) => teams.get(orgId, teamId);
}
