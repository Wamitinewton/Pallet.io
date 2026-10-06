import type { OrgId } from "@/shared/domain/ids";
import { confirmsTeamDeletion, TeamDeletionNotConfirmedError, type Team } from "../domain/team";
import type { TeamRepository } from "./ports";

export interface DeleteTeamCommand {
    readonly orgId: OrgId;
    readonly team: Team;
    readonly confirmation: string;
}

/** The backend asks for no confirmation, so the typed name is checked here and never sent. */
export type DeleteTeam = (command: DeleteTeamCommand) => Promise<void>;

export function makeDeleteTeam(teams: TeamRepository): DeleteTeam {
    return async ({ orgId, team, confirmation }) => {
        if (!confirmsTeamDeletion(team, confirmation)) throw new TeamDeletionNotConfirmedError();
        await teams.delete(orgId, team.id);
    };
}
