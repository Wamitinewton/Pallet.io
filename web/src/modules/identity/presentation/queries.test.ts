import { QueryClient } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import type { CheckSlugAvailability } from "../application/check-slug-availability";
import { identityKeys, identityQueries, SLUG_AVAILABILITY_STALE_TIME_MS } from "./queries";

describe("identityQueries.slugAvailability", () => {
    it("keys by slug under identity and hands the query's abort signal to the use case", async () => {
        const signals: (AbortSignal | undefined)[] = [];
        const check: CheckSlugAvailability = (slug, request) => {
            signals.push(request?.signal);
            return Promise.resolve({ slug, available: true });
        };
        const options = identityQueries.slugAvailability(check, "kilima");

        await new QueryClient().query(options);

        expect(options.queryKey).toEqual(identityKeys.slugAvailability("kilima"));
        expect(options.queryKey.slice(0, 1)).toEqual(identityKeys.all);
        expect(options.staleTime).toBe(SLUG_AVAILABILITY_STALE_TIME_MS);
        expect(signals[0]).toBeInstanceOf(AbortSignal);
    });
});
