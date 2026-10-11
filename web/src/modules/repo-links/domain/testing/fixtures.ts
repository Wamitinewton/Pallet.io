import { asAppId, asInstallationId, asRepoId, asUserId } from "@/shared/domain/ids";
import { asIsoInstant } from "@/shared/domain/instant";
import type { PickerRepository } from "../picker-repository";
import type { DisconnectReason, RepoLink } from "../repo-link";

export const CHECKOUT_APP_ID = asAppId("6f1c2a9e-4b7d-4e21-9a0c-3d5b8e7f1a24");
export const KILIMA_LABS_INSTALLATION = asInstallationId(41000001);

export function aRepoLink(overrides: Partial<RepoLink> = {}): RepoLink {
    return {
        appId: CHECKOUT_APP_ID,
        installationId: KILIMA_LABS_INSTALLATION,
        repoId: asRepoId(72000001),
        repoFullName: "kilima-labs/checkout-api",
        productionBranch: "main",
        rootDirectory: "",
        autoDeploy: true,
        connection: { status: "ACTIVE" },
        warnings: [],
        lastAcceptedHead: {
            branch: "main",
            sha: "3f9c2e1b7d04a8c95e21f06d3b4a7c8e9f1d2a05",
            advancedAt: asIsoInstant("2026-01-15T11:46:00Z"),
            message: "Retry M-Pesa callbacks on gateway timeout",
            author: "fatuma-hassan",
        },
        verification: {
            verifiedByUserId: asUserId("user-amani"),
            githubLogin: "amani-otieno",
            permission: "admin",
            accessVerifiedAt: asIsoInstant("2026-01-10T09:00:00Z"),
            accessCheckedAt: asIsoInstant("2026-01-15T08:00:00Z"),
        },
        version: 3,
        createdAt: asIsoInstant("2026-01-10T09:00:00Z"),
        updatedAt: asIsoInstant("2026-01-12T09:00:00Z"),
        ...overrides,
    };
}

export function aDisconnectedLink(reason: DisconnectReason, overrides: Partial<RepoLink> = {}): RepoLink {
    return aRepoLink({
        connection: { status: "DISCONNECTED", reason, disconnectedAt: asIsoInstant("2026-01-14T09:12:00Z") },
        lastAcceptedHead: null,
        ...overrides,
    });
}

export function aPickerRepository(overrides: Partial<PickerRepository> = {}): PickerRepository {
    return {
        repoId: asRepoId(72000001),
        fullName: "kilima-labs/checkout-api",
        defaultBranch: "main",
        isPrivate: true,
        archived: false,
        permission: "admin",
        linkable: true,
        ...overrides,
    };
}
