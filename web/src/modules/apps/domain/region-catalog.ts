export const CLOUD_PROVIDERS = ["AWS", "GCP"] as const;

export type CloudProvider = (typeof CLOUD_PROVIDERS)[number];

export interface Region {
    readonly id: string;
    /** Where the region is, for people who don't know the provider's ids by heart. */
    readonly location: string;
}

/**
 * Mirrors `pallet.orgteam.apps.regions` in `config-repo/org-team-service.yml`, since no endpoint lists
 * them: change both together. The backend stays the authority and answers a region it no longer allows
 * with `INVALID_REGION`.
 */
export const REGION_CATALOG: Readonly<Record<CloudProvider, readonly Region[]>> = {
    AWS: [
        { id: "us-east-1", location: "N. Virginia" },
        { id: "us-west-2", location: "Oregon" },
        { id: "eu-west-1", location: "Ireland" },
        { id: "eu-central-1", location: "Frankfurt" },
        { id: "ap-southeast-1", location: "Singapore" },
        { id: "af-south-1", location: "Cape Town" },
    ],
    GCP: [
        { id: "us-central1", location: "Iowa" },
        { id: "us-east1", location: "South Carolina" },
        { id: "europe-west1", location: "Belgium" },
        { id: "europe-west4", location: "Netherlands" },
        { id: "asia-southeast1", location: "Singapore" },
        { id: "africa-south1", location: "Johannesburg" },
    ],
};

export function isCloudProvider(value: string): value is CloudProvider {
    return (CLOUD_PROVIDERS as readonly string[]).includes(value);
}

export function regionsFor(provider: CloudProvider): readonly Region[] {
    return REGION_CATALOG[provider];
}

export function isRegionOf(provider: CloudProvider, regionId: string): boolean {
    return regionsFor(provider).some((region) => region.id === regionId);
}

/** Undefined for a region the catalog no longer lists, which an existing app may still run in. */
export function findRegion(provider: CloudProvider, regionId: string): Region | undefined {
    return regionsFor(provider).find((region) => region.id === regionId);
}
