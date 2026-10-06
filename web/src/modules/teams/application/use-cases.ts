import { makeAddTeamMember, type AddTeamMember } from "./add-team-member";
import { makeCreateTeam, type CreateTeam } from "./create-team";
import { makeDeleteTeam, type DeleteTeam } from "./delete-team";
import { makeGetTeam, type GetTeam } from "./get-team";
import { makeListTeamMembers, type ListTeamMembers } from "./list-team-members";
import { makeListTeams, type ListTeams } from "./list-teams";
import type { TeamRepository } from "./ports";
import { makeRemoveTeamMember, type RemoveTeamMember } from "./remove-team-member";
import { makeRenameTeam, type RenameTeam } from "./rename-team";

export interface TeamUseCases {
    readonly listTeams: ListTeams;
    readonly getTeam: GetTeam;
    readonly createTeam: CreateTeam;
    readonly renameTeam: RenameTeam;
    readonly deleteTeam: DeleteTeam;
    readonly listTeamMembers: ListTeamMembers;
    readonly addTeamMember: AddTeamMember;
    readonly removeTeamMember: RemoveTeamMember;
}

export interface TeamDependencies {
    readonly teams: TeamRepository;
}

export function makeTeamUseCases({ teams }: TeamDependencies): TeamUseCases {
    return {
        listTeams: makeListTeams(teams),
        getTeam: makeGetTeam(teams),
        createTeam: makeCreateTeam(teams),
        renameTeam: makeRenameTeam(teams),
        deleteTeam: makeDeleteTeam(teams),
        listTeamMembers: makeListTeamMembers(teams),
        addTeamMember: makeAddTeamMember(teams),
        removeTeamMember: makeRemoveTeamMember(teams),
    };
}
