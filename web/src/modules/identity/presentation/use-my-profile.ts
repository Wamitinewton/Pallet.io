"use client";

import { useQuery } from "@tanstack/react-query";
import type { Profile } from "../domain/profile";
import { useIdentityUseCases } from "./identity-use-cases";
import { identityQueries } from "./queries";

export function useMyProfile(): Profile | undefined {
    const { getMyProfile } = useIdentityUseCases();
    return useQuery(identityQueries.profile(getMyProfile)).data;
}
