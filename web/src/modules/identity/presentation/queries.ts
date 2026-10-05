import { queryOptions } from "@tanstack/react-query";
import type { CheckSlugAvailability } from "../application/check-slug-availability";

export const SLUG_AVAILABILITY_STALE_TIME_MS = 60_000;

export const identityKeys = {
    all: ["identity"] as const,
    slugAvailability: (slug: string) => [...identityKeys.all, "signup", "slug-availability", slug] as const,
};

export const identityQueries = {
    slugAvailability: (checkSlugAvailability: CheckSlugAvailability, slug: string) =>
        queryOptions({
            queryKey: identityKeys.slugAvailability(slug),
            queryFn: ({ signal }) => checkSlugAvailability(slug, { signal }),
            staleTime: SLUG_AVAILABILITY_STALE_TIME_MS,
        }),
};
