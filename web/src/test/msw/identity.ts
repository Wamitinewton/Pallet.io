import type { SessionSummary } from "@/modules/session";
import type { components } from "@/shared/infrastructure/api/generated/identity";

type Schemas = components["schemas"];
export type UserProfileDto = Required<Pick<Schemas["UserProfileResponse"], "sub" | "email" | "displayName" | "status">>;
export type SessionDto = Required<Schemas["SessionResponse"]>;

export function userProfileDto(overrides: Partial<UserProfileDto> = {}): UserProfileDto {
    return {
        sub: "user-amani",
        email: "amani@kilimalabs.co",
        displayName: "Amani Otieno",
        status: "ACTIVE",
        ...overrides,
    };
}

export function sessionDto(overrides: Partial<SessionDto> = {}): SessionDto {
    return {
        id: "kc-this-device",
        ipAddress: "197.237.14.82",
        startedAt: "2026-01-15T08:41:00Z",
        lastAccessedAt: "2026-01-15T11:59:00Z",
        ...overrides,
    };
}

/** What `GET /api/session` answers for the browser that is signed in with `kc-this-device`. */
export function sessionSummaryBody(overrides: Partial<SessionSummary> = {}) {
    return {
        userId: "user-amani",
        email: "amani@kilimalabs.co",
        keycloakSessionId: "kc-this-device",
        accessExpiresAt: "2026-01-15T12:05:00Z",
        ...overrides,
    };
}
