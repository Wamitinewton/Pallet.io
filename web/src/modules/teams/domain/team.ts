import { ApiError } from "@/shared/domain/errors";
import { asTeamId, isUuid, type TeamId, type UserId } from "@/shared/domain/ids";
import type { IsoInstant } from "@/shared/domain/instant";
import type { Role } from "@/shared/domain/role";
import { optionalSlugSchema } from "@/shared/domain/slug";
import { z } from "zod";
import { TEAM_NOT_FOUND } from "./team-errors";

export interface Team {
    readonly id: TeamId;
    readonly name: string;
    /** Set at creation and never changed. */
    readonly slug: string;
    readonly memberCount: number;
    readonly createdAt: IsoInstant;
    readonly updatedAt: IsoInstant;
}

/** An active organization member assigned to a team; the role is their organization-wide one. */
export interface TeamMember {
    readonly userId: UserId;
    readonly email: string;
    readonly displayName: string;
    readonly role: Role;
    readonly joinedAt: IsoInstant;
}

export const MAX_TEAM_NAME_LENGTH = 100;

export const teamNameSchema = z
    .string()
    .trim()
    .min(1, "Enter a name for the team")
    .max(MAX_TEAM_NAME_LENGTH, `Keep the name to ${String(MAX_TEAM_NAME_LENGTH)} characters or fewer`);

export const newTeamSchema = z.object({ name: teamNameSchema, slug: optionalSlugSchema });

export type NewTeamForm = z.input<typeof newTeamSchema>;

export type NewTeam = z.output<typeof newTeamSchema>;

export const renameTeamSchema = z.object({ name: teamNameSchema });

export type RenameTeamForm = z.input<typeof renameTeamSchema>;

export type TeamRename = z.output<typeof renameTeamSchema>;

/** Surrounding blanks never count as a change, since the name is saved trimmed. */
export function isSameTeamName(team: Pick<Team, "name">, typed: string): boolean {
    return typed.trim() === team.name;
}

/** Exactly the team's name as shown: no trimming, no case folding. */
export function confirmsTeamDeletion(team: Pick<Team, "name">, typed: string): boolean {
    return typed === team.name;
}

export class TeamDeletionNotConfirmedError extends Error {
    override readonly name = "TeamDeletionNotConfirmedError";

    constructor() {
        super("The typed confirmation doesn't match the team's name");
    }
}

export function withMemberCountChange(team: Team, change: number): Team {
    return { ...team, memberCount: Math.max(0, team.memberCount + change) };
}

/** The `[teamId]` route segment; anything but a UUID can't name a team, so it never reaches the backend. */
export function teamIdFromParam(value: string): TeamId | undefined {
    return isUuid(value) ? asTeamId(value) : undefined;
}

/** The team is gone, or never existed: the page draws its not-found state for either. */
export function isTeamUnavailable(error: unknown): boolean {
    return error instanceof ApiError && error.is(TEAM_NOT_FOUND);
}
