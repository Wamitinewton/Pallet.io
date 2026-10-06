import { asAppId, asTeamId } from "@/shared/domain/ids";
import { asIsoInstant } from "@/shared/domain/instant";
import type { App } from "../app";

export const PAYMENTS_TEAM_ID = asTeamId("3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b");
export const WEB_TEAM_ID = asTeamId("7c4e2a91-0b3d-4f5e-8a6c-9d1e2f3a4b5c");

export function anApp(overrides: Partial<App> = {}): App {
    return {
        id: asAppId("6f1c2a9e-4b7d-4e21-9a0c-3d5b8e7f1a24"),
        name: "Checkout API",
        slug: "checkout-api",
        cloudProvider: "AWS",
        region: "af-south-1",
        teamId: PAYMENTS_TEAM_ID,
        createdAt: asIsoInstant("2026-01-09T09:00:00Z"),
        updatedAt: asIsoInstant("2026-01-12T09:00:00Z"),
        ...overrides,
    };
}
