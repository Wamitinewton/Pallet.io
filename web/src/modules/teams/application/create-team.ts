import type { OrgId } from "@/shared/domain/ids";
import { newTeamSchema, type NewTeamForm, type Team } from "../domain/team";
import type { TeamRepository } from "./ports";

export type CreateTeam = (orgId: OrgId, team: NewTeamForm) => Promise<Team>;

export function makeCreateTeam(teams: TeamRepository): CreateTeam {
    return async (orgId, team) => teams.create(orgId, newTeamSchema.parse(team));
}
