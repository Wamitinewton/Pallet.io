import { ApiError } from "@/shared/domain/errors";
import type { OrgId, TeamId, UserId } from "@/shared/domain/ids";
import type { TeamMember } from "../domain/team";
import { ALREADY_IN_TEAM } from "../domain/team-errors";
import type { TeamRepository } from "./ports";

/** Someone already on the team is where the caller wanted them, so that conflict is an outcome, not a failure. */
export type AddTeamMemberOutcome =
    { readonly status: "added"; readonly member: TeamMember } | { readonly status: "alreadyInTeam" };

export type AddTeamMember = (orgId: OrgId, teamId: TeamId, userId: UserId) => Promise<AddTeamMemberOutcome>;

export function makeAddTeamMember(teams: TeamRepository): AddTeamMember {
    return async (orgId, teamId, userId) => {
        try {
            return { status: "added", member: await teams.addMember(orgId, teamId, userId) };
        } catch (error) {
            if (error instanceof ApiError && error.is(ALREADY_IN_TEAM)) return { status: "alreadyInTeam" };
            throw error;
        }
    };
}
