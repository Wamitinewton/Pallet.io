import { asTeamId, asUserId } from "@/shared/domain/ids";
import { asIsoInstant } from "@/shared/domain/instant";
import type { Team, TeamMember } from "../team";

export function aTeam(overrides: Partial<Team> = {}): Team {
    return {
        id: asTeamId("3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b"),
        name: "Payments",
        slug: "payments",
        memberCount: 3,
        createdAt: asIsoInstant("2026-01-08T09:00:00Z"),
        updatedAt: asIsoInstant("2026-01-08T09:00:00Z"),
        ...overrides,
    };
}

export function aTeamMember(overrides: Partial<TeamMember> = {}): TeamMember {
    return {
        userId: asUserId("user-grace"),
        email: "grace.njeri@kilimalabs.co",
        displayName: "Grace Njeri",
        role: "ADMIN",
        joinedAt: asIsoInstant("2026-01-04T09:00:00Z"),
        ...overrides,
    };
}
