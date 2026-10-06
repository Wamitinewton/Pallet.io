import { teamPath, type Team } from "@/modules/teams";
import type { OrgId, TeamId } from "@/shared/domain/ids";
import { Skeleton } from "@/shared/presentation/ui";
import Link from "next/link";
import { NO_TEAM_COPY, UNKNOWN_TEAM_COPY } from "./app-copy";
import styles from "./Apps.module.css";

export type TeamDirectory = ReadonlyMap<TeamId, Team>;

export function byTeamId(teams: readonly Team[]): TeamDirectory {
    return new Map(teams.map((team) => [team.id, team]));
}

export interface AppTeamProps {
    readonly orgId: OrgId;
    readonly teamId: TeamId | null;
    /** Undefined while the organization's teams are read. */
    readonly teams: TeamDirectory | undefined;
    readonly link?: boolean;
}

/** The team that owns an app, by name; a team missing from the directory was most likely just created. */
export function AppTeam({ orgId, teamId, teams, link = false }: AppTeamProps) {
    if (teamId === null) return <span className={styles.faint}>{NO_TEAM_COPY}</span>;
    if (teams === undefined) return <Skeleton width={72} height={12} />;
    const team = teams.get(teamId);
    if (team === undefined) return <span className={styles.faint}>{UNKNOWN_TEAM_COPY}</span>;
    return link ? <Link href={teamPath(orgId, team.id)}>{team.name}</Link> : <>{team.name}</>;
}
