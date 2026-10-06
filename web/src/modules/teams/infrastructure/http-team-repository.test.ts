import { anonymousAccessTokens } from "@/shared/application/access-token";
import { ApiError, UnexpectedResponseError } from "@/shared/domain/errors";
import { asOrgId, asTeamId, asUserId } from "@/shared/domain/ids";
import { createApiClients } from "@/shared/infrastructure/api/clients";
import { serverTransport } from "@/shared/infrastructure/api/server-transport";
import { beforeEach, describe, expect, it } from "vitest";
import type { TeamRepository } from "../application/ports";
import { DEFAULT_TEAM_LIST_PARAMS, teamListQuery, teamMemberListQuery } from "../domain/team-list-query";
import { httpTeamRepository } from "./http-team-repository";

const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const ok = (data: unknown, message = "OK") => json(200, { success: true, message, data });
const failure = (status: number, code: string) =>
    json(status, { success: false, message: "Refused", error: code, statusCode: status });

const TEAM_ID = "3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b";
const TEAMS_URL = "http://gateway.test/api/v1/org-team/orgs/org-1/teams";

const teamDto = {
    id: TEAM_ID,
    name: "Payments",
    slug: "payments",
    memberCount: 3,
    createdAt: "2026-01-08T09:00:00Z",
    updatedAt: "2026-01-09T09:00:00Z",
};

const memberDto = {
    userId: "user-grace",
    email: "grace.njeri@kilimalabs.co",
    displayName: "Grace Njeri",
    role: "ADMIN",
    status: "ACTIVE",
    joinedAt: "2026-01-04T09:00:00Z",
};

const pageOf = (items: unknown[], { page = 0, size = 20, totalElements = items.length } = {}) =>
    ok({
        content: items,
        page,
        size,
        totalElements,
        totalPages: Math.ceil(totalElements / size),
        first: page === 0,
        last: (page + 1) * size >= totalElements,
    });

const orgId = asOrgId("org-1");
const teamId = asTeamId(TEAM_ID);
const userId = asUserId("user-grace");

let requests: Request[];
let reply: () => Response;
let repository: TeamRepository;

beforeEach(() => {
    requests = [];
    reply = () => ok(teamDto);
    const transport = serverTransport({
        gatewayUrl: "http://gateway.test",
        tokens: anonymousAccessTokens,
        fetch: (request) => {
            requests.push(request);
            return Promise.resolve(reply());
        },
    });
    repository = httpTeamRepository(createApiClients(transport).orgTeam);
});

const lastUrl = () => new URL(requests.at(-1)?.url ?? "");

describe("httpTeamRepository.list", () => {
    it("asks for one page in the order the query carries", async () => {
        reply = () => pageOf([teamDto], { page: 1, size: 24, totalElements: 25 });

        const page = await repository.list(
            orgId,
            teamListQuery({ ...DEFAULT_TEAM_LIST_PARAMS, sort: { field: "createdAt", direction: "desc" }, page: 2 }),
        );

        expect(lastUrl().pathname).toBe("/api/v1/org-team/orgs/org-1/teams");
        expect(Object.fromEntries(lastUrl().searchParams)).toEqual({ page: "1", size: "24", sort: "createdAt,desc" });
        expect(page).toMatchObject({ page: 1, totalItems: 25, totalPages: 2, isLast: true });
        expect(page.items).toEqual([teamDto]);
    });

    it("refuses a team whose count breaks the contract", async () => {
        reply = () => pageOf([{ ...teamDto, memberCount: -1 }]);

        await expect(repository.list(orgId, teamListQuery(DEFAULT_TEAM_LIST_PARAMS))).rejects.toBeInstanceOf(
            UnexpectedResponseError,
        );
    });
});

describe("httpTeamRepository.get", () => {
    it("reads one team", async () => {
        expect(await repository.get(orgId, teamId)).toEqual(teamDto);
        expect(requests[0]?.url).toBe(`${TEAMS_URL}/${TEAM_ID}`);
    });

    it("raises TEAM_NOT_FOUND as the backend reports it", async () => {
        reply = () => failure(404, "TEAM_NOT_FOUND");

        await expect(repository.get(orgId, teamId)).rejects.toMatchObject({
            constructor: ApiError,
            code: "TEAM_NOT_FOUND",
        });
    });
});

describe("httpTeamRepository.create", () => {
    it("sends the name and the chosen slug", async () => {
        reply = () => json(201, { success: true, message: "Team created", data: teamDto });

        expect(await repository.create(orgId, { name: "Payments", slug: "payments" })).toEqual(teamDto);
        expect(requests[0]?.method).toBe("POST");
        expect(requests[0]?.url).toBe(TEAMS_URL);
        expect(await requests[0]?.json()).toEqual({ name: "Payments", slug: "payments" });
    });

    it("leaves the slug out for the backend to derive", async () => {
        reply = () => json(201, { success: true, message: "Team created", data: teamDto });

        await repository.create(orgId, { name: "Payments", slug: undefined });

        expect(await requests[0]?.json()).toEqual({ name: "Payments" });
    });

    it.each([
        [409, "SLUG_TAKEN"],
        [409, "QUOTA_EXCEEDED"],
    ])("raises %s %s as the backend reports it", async (status, code) => {
        reply = () => failure(status, code);

        await expect(repository.create(orgId, { name: "Payments", slug: undefined })).rejects.toMatchObject({ code });
    });
});

describe("httpTeamRepository.rename", () => {
    it("sends the name only", async () => {
        reply = () => ok({ ...teamDto, name: "Billing" }, "Team updated");

        expect(await repository.rename(orgId, teamId, { name: "Billing" })).toMatchObject({
            name: "Billing",
            slug: "payments",
        });
        expect(requests[0]?.method).toBe("PATCH");
        expect(await requests[0]?.json()).toEqual({ name: "Billing" });
    });
});

describe("httpTeamRepository.delete", () => {
    it("deletes the team and expects no content", async () => {
        reply = () => new Response(null, { status: 204 });

        await repository.delete(orgId, teamId);

        expect(requests[0]?.method).toBe("DELETE");
        expect(requests[0]?.url).toBe(`${TEAMS_URL}/${TEAM_ID}`);
    });

    it("refuses a body where no content was promised", async () => {
        reply = () => ok(null);

        await expect(repository.delete(orgId, teamId)).rejects.toBeInstanceOf(UnexpectedResponseError);
    });
});

describe("httpTeamRepository.listMembers", () => {
    it("reads one page of the team's people", async () => {
        reply = () => pageOf([memberDto], { page: 1, size: 20, totalElements: 21 });

        const page = await repository.listMembers(orgId, teamId, teamMemberListQuery(2));

        expect(lastUrl().pathname).toBe(`/api/v1/org-team/orgs/org-1/teams/${TEAM_ID}/members`);
        expect(Object.fromEntries(lastUrl().searchParams)).toEqual({ page: "1", size: "20" });
        expect(page.items).toEqual([
            {
                userId: "user-grace",
                email: "grace.njeri@kilimalabs.co",
                displayName: "Grace Njeri",
                role: "ADMIN",
                joinedAt: "2026-01-04T09:00:00Z",
            },
        ]);
    });
});

describe("httpTeamRepository.addMember", () => {
    it("posts the user id and reads the member back", async () => {
        reply = () => json(201, { success: true, message: "Team member added", data: memberDto });

        expect(await repository.addMember(orgId, teamId, userId)).toMatchObject({ userId: "user-grace" });
        expect(requests[0]?.method).toBe("POST");
        expect(requests[0]?.url).toBe(`${TEAMS_URL}/${TEAM_ID}/members`);
        expect(await requests[0]?.json()).toEqual({ userId: "user-grace" });
    });

    it.each([
        [409, "ALREADY_IN_TEAM"],
        [404, "MEMBER_NOT_FOUND"],
    ])("raises %s %s as the backend reports it", async (status, code) => {
        reply = () => failure(status, code);

        await expect(repository.addMember(orgId, teamId, userId)).rejects.toMatchObject({ code });
    });
});

describe("httpTeamRepository.removeMember", () => {
    it("deletes the assignment and expects no content", async () => {
        reply = () => new Response(null, { status: 204 });

        await repository.removeMember(orgId, teamId, userId);

        expect(requests[0]?.method).toBe("DELETE");
        expect(requests[0]?.url).toBe(`${TEAMS_URL}/${TEAM_ID}/members/user-grace`);
    });
});
