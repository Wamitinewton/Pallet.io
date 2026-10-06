import { describe, expect, it } from "vitest";
import { CLOUD_PROVIDERS, findRegion, isCloudProvider, isRegionOf, REGION_CATALOG, regionsFor } from "./region-catalog";

describe("REGION_CATALOG", () => {
    it.each(CLOUD_PROVIDERS)("lists regions for %s", (provider) => {
        expect(regionsFor(provider).length).toBeGreaterThan(0);
    });

    it("never lists a region id twice, even across providers", () => {
        const ids = Object.values(REGION_CATALOG).flatMap((regions) => regions.map((region) => region.id));

        expect(new Set(ids).size).toBe(ids.length);
    });

    it("names where every region is", () => {
        for (const region of Object.values(REGION_CATALOG).flat()) {
            expect(region.location.trim()).not.toBe("");
        }
    });
});

describe("isRegionOf", () => {
    it("accepts a region only for its own provider", () => {
        expect(isRegionOf("AWS", "eu-west-1")).toBe(true);
        expect(isRegionOf("GCP", "eu-west-1")).toBe(false);
        expect(isRegionOf("GCP", "europe-west1")).toBe(true);
        expect(isRegionOf("AWS", "")).toBe(false);
    });
});

describe("findRegion", () => {
    it("finds a listed region and nothing for one the catalog dropped", () => {
        expect(findRegion("AWS", "af-south-1")).toEqual({ id: "af-south-1", location: "Cape Town" });
        expect(findRegion("AWS", "sa-east-1")).toBeUndefined();
    });
});

describe("isCloudProvider", () => {
    it.each([
        ["AWS", true],
        ["GCP", true],
        ["aws", false],
        ["AZURE", false],
        ["", false],
    ])("%j is a provider: %s", (value, expected) => {
        expect(isCloudProvider(value)).toBe(expected);
    });
});
