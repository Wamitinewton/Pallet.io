import type { OrgId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import type { Team } from "../domain/team";
import type { TeamListQuery } from "../domain/team-list-query";
import type { TeamRepository } from "./ports";

export type ListTeams = (orgId: OrgId, query: TeamListQuery) => Promise<Page<Team>>;

export function makeListTeams(teams: TeamRepository): ListTeams {
    return (orgId, query) => teams.list(orgId, query);
}
