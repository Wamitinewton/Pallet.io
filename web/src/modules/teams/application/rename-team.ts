import type { OrgId } from "@/shared/domain/ids";
import { isSameTeamName, renameTeamSchema, type RenameTeamForm, type Team } from "../domain/team";
import type { TeamRepository } from "./ports";

export interface RenameTeamCommand {
    readonly orgId: OrgId;
    readonly team: Team;
    readonly rename: RenameTeamForm;
}

/** Saving the name the team already has changes nothing, so it is never sent. */
export type RenameTeam = (command: RenameTeamCommand) => Promise<Team>;

export function makeRenameTeam(teams: TeamRepository): RenameTeam {
    return async ({ orgId, team, rename }) => {
        const parsed = renameTeamSchema.parse(rename);
        if (isSameTeamName(team, parsed.name)) return team;
        return teams.rename(orgId, team.id, parsed);
    };
}
