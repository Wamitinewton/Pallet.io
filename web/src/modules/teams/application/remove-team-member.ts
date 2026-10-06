import { ApiError } from "@/shared/domain/errors";
import type { OrgId, TeamId, UserId } from "@/shared/domain/ids";
import { MEMBER_NOT_FOUND } from "../domain/team-errors";
import type { TeamRepository } from "./ports";

/** Someone already off the team is where the caller wanted them, so that answer is an outcome, not a failure. */
export type RemoveTeamMemberOutcome = "removed" | "alreadyRemoved";

export type RemoveTeamMember = (orgId: OrgId, teamId: TeamId, userId: UserId) => Promise<RemoveTeamMemberOutcome>;

export function makeRemoveTeamMember(teams: TeamRepository): RemoveTeamMember {
    return async (orgId, teamId, userId) => {
        try {
            await teams.removeMember(orgId, teamId, userId);
            return "removed";
        } catch (error) {
            if (error instanceof ApiError && error.is(MEMBER_NOT_FOUND)) return "alreadyRemoved";
            throw error;
        }
    };
}
