import type { components } from "@/shared/infrastructure/api/generated/org-team";

type Schemas = components["schemas"];
export type OrgSummaryDto = Required<Schemas["OrgSummaryDto"]>;
export type OrgDto = Required<Omit<Schemas["OrgDto"], "counts">> & { counts: Required<Schemas["Counts"]> };
export type MemberDto = Required<Schemas["MemberDto"]>;
export type InviteDto = Required<Schemas["InviteDto"]>;
export type TeamDto = Required<Schemas["TeamDto"]>;

export function orgSummaryDto(overrides: Partial<OrgSummaryDto> = {}): OrgSummaryDto {
    return {
        orgId: "org-kilima",
        name: "Kilima Labs",
        slug: "kilima-labs",
        kind: "TEAM",
        myRole: "OWNER",
        ...overrides,
    };
}

export function orgDto(overrides: Partial<OrgDto> = {}): OrgDto {
    return {
        orgId: "org-kilima",
        name: "Kilima Labs",
        slug: "kilima-labs",
        kind: "TEAM",
        status: "ACTIVE",
        ownerUserId: "user-amani",
        createdAt: "2026-01-02T09:00:00Z",
        counts: { members: 6, teams: 3, apps: 4 },
        ...overrides,
    };
}

export function memberDto(overrides: Partial<MemberDto> = {}): MemberDto {
    return {
        userId: "user-amani",
        email: "amani@kilimalabs.co",
        displayName: "Amani Otieno",
        role: "OWNER",
        status: "ACTIVE",
        joinedAt: "2026-01-02T09:00:00Z",
        ...overrides,
    };
}

export function inviteDto(overrides: Partial<InviteDto> = {}): InviteDto {
    return {
        id: "6f1c2a52-3d4e-4b7a-9c1d-2e3f4a5b6c7d",
        email: "david.kariuki@kilimalabs.co",
        role: "DEVELOPER",
        status: "PENDING",
        invitedByUserId: "user-grace",
        sendCount: 2,
        expiresAt: "2026-01-16T12:00:00Z",
        createdAt: "2026-01-13T12:00:00Z",
        ...overrides,
    };
}

export type InvitePreviewDto = Required<Schemas["InvitePreviewDto"]>;

export function invitePreviewDto(overrides: Partial<InvitePreviewDto> = {}): InvitePreviewDto {
    return {
        orgName: "Kilima Labs",
        role: "DEVELOPER",
        inviterName: "Grace Njeri",
        maskedEmail: "d***@kilimalabs.co",
        expiresAt: "2026-01-17T12:00:00Z",
        ...overrides,
    };
}

export function teamDto(overrides: Partial<TeamDto> = {}): TeamDto {
    return {
        id: "3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b",
        name: "Payments",
        slug: "payments",
        memberCount: 0,
        createdAt: "2026-01-08T09:00:00Z",
        updatedAt: "2026-01-08T09:00:00Z",
        ...overrides,
    };
}

/** `teamId` is null, not absent, for an app without a team. */
export type AppDto = Omit<Required<Schemas["AppDto"]>, "teamId"> & { teamId: string | null };

export function appDto(overrides: Partial<AppDto> = {}): AppDto {
    return {
        id: "6f1c2a9e-4b7d-4e21-9a0c-3d5b8e7f1a24",
        name: "Checkout API",
        slug: "checkout-api",
        cloudProvider: "AWS",
        region: "af-south-1",
        teamId: "3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b",
        createdAt: "2026-01-09T09:00:00Z",
        updatedAt: "2026-01-12T09:00:00Z",
        ...overrides,
    };
}
