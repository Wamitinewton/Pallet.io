import { anonymousAccessTokens } from "@/shared/application/access-token";
import { ApiError, UnexpectedResponseError } from "@/shared/domain/errors";
import { asOrgId, asUserId } from "@/shared/domain/ids";
import { createApiClients } from "@/shared/infrastructure/api/clients";
import { serverTransport } from "@/shared/infrastructure/api/server-transport";
import { beforeEach, describe, expect, it } from "vitest";
import type { MemberRepository } from "../application/ports";
import { aMemberListQuery } from "../domain/testing/fixtures";
import { httpMemberRepository } from "./http-member-repository";

const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const memberDto = {
    userId: "user-1",
    email: "amani@kilimalabs.co",
    displayName: "Amani Otieno",
    role: "DEVELOPER",
    status: "ACTIVE",
    joinedAt: "2026-01-02T09:00:00Z",
};

let requests: Request[];
let reply: () => Response;
let repository: MemberRepository;

beforeEach(() => {
    requests = [];
    reply = () => json(200, { success: true, message: "OK", data: memberDto });
    const transport = serverTransport({
        gatewayUrl: "http://gateway.test",
        tokens: anonymousAccessTokens,
        fetch: (request) => {
            requests.push(request);
            return Promise.resolve(reply());
        },
    });
    repository = httpMemberRepository(createApiClients(transport).orgTeam);
});

describe("httpMemberRepository.getMine", () => {
    it("reads the caller's membership in the organization", async () => {
        expect(await repository.getMine(asOrgId("org-1"))).toEqual(memberDto);
        expect(requests[0]?.url).toBe("http://gateway.test/api/v1/org-team/orgs/org-1/members/me");
    });

    it("raises NOT_A_MEMBER as the backend reports it", async () => {
        reply = () => json(403, { success: false, message: "Not a member", error: "NOT_A_MEMBER", statusCode: 403 });

        await expect(repository.getMine(asOrgId("org-1"))).rejects.toMatchObject({
            constructor: ApiError,
            code: "NOT_A_MEMBER",
        });
    });

    it("refuses a role the client does not know", async () => {
        reply = () => json(200, { success: true, message: "OK", data: { ...memberDto, role: "ROOT" } });

        await expect(repository.getMine(asOrgId("org-1"))).rejects.toBeInstanceOf(UnexpectedResponseError);
    });
});

const pageOf = (items: unknown[], { page = 0, size = 20, totalElements = items.length } = {}) => ({
    success: true,
    message: "Members retrieved",
    data: {
        content: items,
        page,
        size,
        totalElements,
        totalPages: Math.ceil(totalElements / size),
        first: page === 0,
        last: (page + 1) * size >= totalElements,
    },
});

const orgId = asOrgId("org-1");
const userId = asUserId("user-1");

describe("httpMemberRepository.list", () => {
    it("asks for one page with the sort, status, role and search the query carries", async () => {
        reply = () => json(200, pageOf([memberDto], { page: 1, size: 10, totalElements: 11 }));

        const page = await repository.list(
            orgId,
            aMemberListQuery({
                q: "ama",
                role: "DEVELOPER",
                status: "REMOVED",
                sort: { field: "joinedAt", direction: "desc" },
                page: 1,
                size: 10,
            }),
        );

        const url = new URL(requests[0]?.url ?? "");
        expect(url.pathname).toBe("/api/v1/org-team/orgs/org-1/members");
        expect(Object.fromEntries(url.searchParams)).toEqual({
            page: "1",
            size: "10",
            sort: "joinedAt,desc",
            status: "REMOVED",
            role: "DEVELOPER",
            q: "ama",
        });
        expect(page).toMatchObject({ items: [memberDto], page: 1, totalItems: 11, totalPages: 2, isLast: true });
    });

    it("leaves out a role and a search that aren't set", async () => {
        reply = () => json(200, pageOf([]));

        await repository.list(orgId, aMemberListQuery());

        expect(Object.fromEntries(new URL(requests[0]?.url ?? "").searchParams)).toEqual({
            page: "0",
            size: "20",
            sort: "displayName,asc",
            status: "ACTIVE",
        });
    });

    it("refuses a page whose rows break the contract", async () => {
        reply = () => json(200, pageOf([{ ...memberDto, status: "SUSPENDED" }]));

        await expect(repository.list(orgId, aMemberListQuery())).rejects.toBeInstanceOf(UnexpectedResponseError);
    });
});

describe("httpMemberRepository.changeRole", () => {
    it("sends the new role and reads the member back", async () => {
        reply = () =>
            json(200, { success: true, message: "Member role updated", data: { ...memberDto, role: "VIEWER" } });

        expect(await repository.changeRole(orgId, userId, "VIEWER")).toMatchObject({ role: "VIEWER" });
        expect(requests[0]?.method).toBe("PATCH");
        expect(requests[0]?.url).toBe("http://gateway.test/api/v1/org-team/orgs/org-1/members/user-1");
        expect(await requests[0]?.json()).toEqual({ role: "VIEWER" });
    });

    it.each(["LAST_OWNER", "INVALID_ROLE_TRANSITION", "CONCURRENT_MODIFICATION"])(
        "raises %s as the backend reports it",
        async (code) => {
            reply = () => json(409, { success: false, message: "Conflict", error: code, statusCode: 409 });

            await expect(repository.changeRole(orgId, userId, "VIEWER")).rejects.toMatchObject({
                constructor: ApiError,
                code,
            });
        },
    );
});

describe("httpMemberRepository.remove", () => {
    it("deletes the membership and expects no content", async () => {
        reply = () => new Response(null, { status: 204 });

        await repository.remove(orgId, userId);

        expect(requests[0]?.method).toBe("DELETE");
        expect(requests[0]?.url).toBe("http://gateway.test/api/v1/org-team/orgs/org-1/members/user-1");
    });

    it("raises MEMBER_NOT_FOUND for someone already removed", async () => {
        reply = () =>
            json(404, { success: false, message: "Member not found.", error: "MEMBER_NOT_FOUND", statusCode: 404 });

        await expect(repository.remove(orgId, userId)).rejects.toMatchObject({ code: "MEMBER_NOT_FOUND" });
    });
});

describe("httpMemberRepository.transferOwnership", () => {
    it("posts the transfer and answers with the new owner", async () => {
        reply = () =>
            json(200, { success: true, message: "Ownership transferred", data: { ...memberDto, role: "OWNER" } });

        expect(await repository.transferOwnership(orgId, userId)).toMatchObject({ userId: "user-1", role: "OWNER" });
        expect(requests[0]?.method).toBe("POST");
        expect(requests[0]?.url).toBe(
            "http://gateway.test/api/v1/org-team/orgs/org-1/members/user-1/transfer-ownership",
        );
    });

    it("raises REAUTHENTICATION_REQUIRED for a stale sign-in", async () => {
        reply = () =>
            json(403, {
                success: false,
                message: "Recent sign-in required",
                error: "REAUTHENTICATION_REQUIRED",
                statusCode: 403,
            });

        await expect(repository.transferOwnership(orgId, userId)).rejects.toMatchObject({
            code: "REAUTHENTICATION_REQUIRED",
        });
    });
});
