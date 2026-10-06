import { queryOptions } from "@tanstack/react-query";
import type { CheckSlugAvailability } from "../application/check-slug-availability";
import type { GetMyProfile } from "../application/get-my-profile";
import type { ListSessions } from "../application/list-sessions";

export const SLUG_AVAILABILITY_STALE_TIME_MS = 60_000;

export const identityKeys = {
    all: ["identity"] as const,
    slugAvailability: (slug: string) => [...identityKeys.all, "signup", "slug-availability", slug] as const,
    me: () => [...identityKeys.all, "me"] as const,
    profile: () => [...identityKeys.me(), "profile"] as const,
    sessions: () => [...identityKeys.me(), "sessions"] as const,
};

export const identityQueries = {
    slugAvailability: (checkSlugAvailability: CheckSlugAvailability, slug: string) =>
        queryOptions({
            queryKey: identityKeys.slugAvailability(slug),
            queryFn: ({ signal }) => checkSlugAvailability(slug, { signal }),
            staleTime: SLUG_AVAILABILITY_STALE_TIME_MS,
        }),
    profile: (getMyProfile: GetMyProfile) =>
        queryOptions({
            queryKey: identityKeys.profile(),
            queryFn: () => getMyProfile(),
        }),
    sessions: (listSessions: ListSessions) =>
        queryOptions({
            queryKey: identityKeys.sessions(),
            queryFn: () => listSessions(),
        }),
};
