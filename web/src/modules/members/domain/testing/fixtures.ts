import { asUserId } from "@/shared/domain/ids";
import { asIsoInstant } from "@/shared/domain/instant";
import type { Member } from "../member";
import { DEFAULT_MEMBER_LIST_PARAMS, type MemberListQuery } from "../member-list-query";

export function aMember(overrides: Partial<Member> = {}): Member {
    return {
        userId: asUserId("user-amani"),
        email: "amani@kilimalabs.co",
        displayName: "Amani Otieno",
        role: "OWNER",
        status: "ACTIVE",
        joinedAt: asIsoInstant("2026-01-02T09:00:00Z"),
        ...overrides,
    };
}

export function aMemberListQuery(overrides: Partial<MemberListQuery> = {}): MemberListQuery {
    return { ...DEFAULT_MEMBER_LIST_PARAMS, page: 0, ...overrides };
}
