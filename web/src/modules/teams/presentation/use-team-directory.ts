"use client";

import type { OrgId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import { useQuery, type UseQueryResult } from "@tanstack/react-query";
import type { Team } from "../domain/team";
import { teamQueries } from "./queries";
import { useTeamUseCases } from "./team-use-cases";

function teamsOf(page: Page<Team>): readonly Team[] {
    return page.items;
}

/** Every team in the organization, by name, for anything that offers a choice of them. */
export function useTeamDirectory(orgId: OrgId): UseQueryResult<readonly Team[]> {
    const { listTeams } = useTeamUseCases();
    return useQuery({ ...teamQueries.directory(listTeams, orgId), select: teamsOf });
}
