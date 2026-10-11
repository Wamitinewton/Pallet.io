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

export type RepoLinkDto = Required<
    Omit<Schemas["RepoLinkDto"], "disconnectedAt" | "lastAcceptedHead" | "verification">
> & {
    disconnectedAt: string | null;
    lastAcceptedHead: AcceptedHeadDto | null;
    verification: Required<Schemas["Verification"]> | null;
};
export type AcceptedHeadDto = Required<Schemas["Head"]>;
export type PickerRepositoryDto = Required<Schemas["PickerRepositoryResponse"]>;
export type ManualBuildResultDto = Required<Schemas["ManualBuildResultDto"]>;

export function acceptedHeadDto(overrides: Partial<AcceptedHeadDto> = {}): AcceptedHeadDto {
    return {
        branch: "main",
        sha: "3f9c2e1b7d04a8c95e21f06d3b4a7c8e9f1d2a05",
        advancedAt: "2026-01-15T11:46:00Z",
        message: "Retry M-Pesa callbacks on gateway timeout",
        author: "fatuma-hassan",
        ...overrides,
    };
}

export function repoLinkDto(overrides: Partial<RepoLinkDto> = {}): RepoLinkDto {
    return {
        appId: "6f1c2a9e-4b7d-4e21-9a0c-3d5b8e7f1a24",
        installationId: 41000001,
        repoId: 72000001,
        repoFullName: "kilima-labs/checkout-api",
        productionBranch: "main",
        rootDirectory: "",
        autoDeploy: true,
        status: "ACTIVE",
        disconnectReason: null,
        disconnectedAt: null,
        warnings: [],
        lastAcceptedHead: acceptedHeadDto(),
        verification: {
            verifiedByUserId: "user-amani",
            githubLogin: "amani-otieno",
            permission: "admin",
            accessVerifiedAt: "2026-01-10T09:00:00Z",
            accessCheckedAt: "2026-01-15T08:00:00Z",
        },
        version: 3,
        createdAt: "2026-01-10T09:00:00Z",
        updatedAt: "2026-01-12T09:00:00Z",
        ...overrides,
    };
}

/** As the service writes it: a disconnected link says why and when, and has no accepted head left. */
export function disconnectedRepoLinkDto(reason: string, overrides: Partial<RepoLinkDto> = {}): RepoLinkDto {
    return repoLinkDto({
        status: "DISCONNECTED",
        disconnectReason: reason,
        disconnectedAt: "2026-01-14T09:12:00Z",
        lastAcceptedHead: null,
        ...overrides,
    });
}

export function pickerRepositoryDto(overrides: Partial<PickerRepositoryDto> = {}): PickerRepositoryDto {
    return {
        repoId: 72000001,
        fullName: "kilima-labs/checkout-api",
        defaultBranch: "main",
        private: true,
        archived: false,
        permission: "admin",
        linkable: true,
        ...overrides,
    };
}
