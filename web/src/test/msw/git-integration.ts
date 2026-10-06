import type { components } from "@/shared/infrastructure/api/generated/git-integration";

type Schemas = components["schemas"];
export type GitHubSessionDto = Required<Schemas["GitHubSessionResponse"]>;
export type InstallationLinkDto = Required<Schemas["InstallationLinkResponse"]>;
export type VisibleInstallationDto = Required<Schemas["GitHubInstallationResponse"]>;

export function githubSessionDto(overrides: Partial<GitHubSessionDto> = {}): GitHubSessionDto {
    return { githubLogin: "amani-otieno", expiresAt: "2026-01-15T13:00:00Z", ...overrides };
}

export function installationLinkDto(overrides: Partial<InstallationLinkDto> = {}): InstallationLinkDto {
    return {
        installationId: 41000001,
        accountLogin: "kilima-labs",
        accountType: "Organization",
        status: "ACTIVE",
        linkedByUserId: "user-amani",
        linkedAt: "2026-01-10T09:00:00Z",
        ...overrides,
    };
}

export function visibleInstallationDto(overrides: Partial<VisibleInstallationDto> = {}): VisibleInstallationDto {
    return {
        installationId: 41000001,
        accountLogin: "kilima-labs",
        accountType: "Organization",
        suspended: false,
        ...overrides,
    };
}
