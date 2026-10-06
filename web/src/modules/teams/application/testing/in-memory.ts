import { asTeamId, type OrgId, type TeamId, type UserId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import type { NewTeam, Team, TeamMember, TeamRename } from "../../domain/team";
import type { TeamListQuery, TeamMemberListQuery } from "../../domain/team-list-query";
import { aTeam } from "../../domain/testing/fixtures";
import type { TeamRepository } from "../ports";

function pageOf<T>(all: readonly T[], { page, size }: { page: number; size: number }): Page<T> {
    const totalPages = Math.ceil(all.length / size);
    return {
        items: all.slice(page * size, (page + 1) * size),
        page,
        size,
        totalItems: all.length,
        totalPages,
        isFirst: page === 0,
        isLast: page >= totalPages - 1,
    };
}

export class InMemoryTeamRepository implements TeamRepository {
    readonly created: { readonly orgId: OrgId; readonly team: NewTeam }[] = [];
    readonly renames: { readonly orgId: OrgId; readonly teamId: TeamId; readonly rename: TeamRename }[] = [];
    readonly deletions: { readonly orgId: OrgId; readonly teamId: TeamId }[] = [];
    readonly additions: { readonly teamId: TeamId; readonly userId: UserId }[] = [];
    readonly removals: { readonly teamId: TeamId; readonly userId: UserId }[] = [];
    /** Thrown by the next call, then cleared. */
    failNext: Error | undefined;

    constructor(
        private teams: Team[] = [aTeam()],
        private readonly people: TeamMember[] = [],
        private readonly roster = new Map<TeamId, UserId[]>(),
    ) {}

    list(_orgId: OrgId, query: TeamListQuery): Promise<Page<Team>> {
        return this.answer(() => pageOf(this.teams, query));
    }

    get(_orgId: OrgId, teamId: TeamId): Promise<Team> {
        return this.answer(() => this.find(teamId));
    }

    create(orgId: OrgId, team: NewTeam): Promise<Team> {
        return this.answer(() => {
            this.created.push({ orgId, team });
            const saved = aTeam({
                id: asTeamId(`team-${String(this.teams.length + 1)}`),
                name: team.name,
                slug: team.slug ?? team.name.toLowerCase(),
                memberCount: 0,
            });
            this.teams.push(saved);
            return saved;
        });
    }

    rename(orgId: OrgId, teamId: TeamId, rename: TeamRename): Promise<Team> {
        return this.answer(() => {
            this.renames.push({ orgId, teamId, rename });
            return { ...this.find(teamId), name: rename.name };
        });
    }

    delete(orgId: OrgId, teamId: TeamId): Promise<void> {
        return this.answer(() => {
            this.deletions.push({ orgId, teamId });
            this.teams = this.teams.filter((team) => team.id !== teamId);
        });
    }

    listMembers(_orgId: OrgId, teamId: TeamId, query: TeamMemberListQuery): Promise<Page<TeamMember>> {
        return this.answer(() => {
            const ids = this.roster.get(teamId) ?? [];
            return pageOf(
                this.people.filter((person) => ids.includes(person.userId)),
                query,
            );
        });
    }

    addMember(_orgId: OrgId, teamId: TeamId, userId: UserId): Promise<TeamMember> {
        return this.answer(() => {
            this.additions.push({ teamId, userId });
            this.roster.set(teamId, [...(this.roster.get(teamId) ?? []), userId]);
            const person = this.people.find((candidate) => candidate.userId === userId);
            if (person === undefined) throw new Error(`No member ${userId}`);
            return person;
        });
    }

    removeMember(_orgId: OrgId, teamId: TeamId, userId: UserId): Promise<void> {
        return this.answer(() => {
            this.removals.push({ teamId, userId });
            this.roster.set(
                teamId,
                (this.roster.get(teamId) ?? []).filter((id) => id !== userId),
            );
        });
    }

    private answer<T>(work: () => T): Promise<T> {
        const failure = this.failNext;
        this.failNext = undefined;
        if (failure !== undefined) return Promise.reject(failure);
        try {
            return Promise.resolve(work());
        } catch (error) {
            return Promise.reject(error instanceof Error ? error : new Error(String(error)));
        }
    }

    private find(teamId: TeamId): Team {
        const team = this.teams.find((candidate) => candidate.id === teamId);
        if (team === undefined) throw new Error(`No team ${teamId}`);
        return team;
    }
}
