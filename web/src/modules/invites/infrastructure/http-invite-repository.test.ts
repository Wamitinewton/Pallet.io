import { anonymousAccessTokens } from "@/shared/application/access-token";
import { ApiError, UnexpectedResponseError } from "@/shared/domain/errors";
import { asInviteId, asOrgId } from "@/shared/domain/ids";
import { createApiClients } from "@/shared/infrastructure/api/clients";
import { serverTransport } from "@/shared/infrastructure/api/server-transport";
import { beforeEach, describe, expect, it } from "vitest";
import type { InviteRepository } from "../application/ports";
import { ALL_PENDING_QUERY, RECENT_INVITES_QUERY } from "../domain/invite-list-query";
import { httpInviteRepository } from "./http-invite-repository";

const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const conflict = (code: string) => json(409, { success: false, message: "Conflict", error: code, statusCode: 409 });

const inviteDto = {
    id: "6f1c2a52-3d4e-4b7a-9c1d-2e3f4a5b6c7d",
    email: "david.kariuki@kilimalabs.co",
    role: "DEVELOPER",
    status: "PENDING",
    invitedByUserId: "user-grace",
    sendCount: 2,
    expiresAt: "2026-01-17T12:00:00Z",
    createdAt: "2026-01-14T12:00:00Z",
};

const pageOf = (items: unknown[], { page = 0, size = 20 } = {}) => ({
    success: true,
    message: "Invites retrieved",
    data: {
        content: items,
        page,
        size,
        totalElements: items.length,
        totalPages: 1,
        first: true,
        last: true,
    },
});

const orgId = asOrgId("org-1");
const inviteId = asInviteId(inviteDto.id);

let requests: Request[];
let reply: () => Response;
let repository: InviteRepository;

beforeEach(() => {
    requests = [];
    reply = () => json(200, { success: true, message: "OK", data: inviteDto });
    const transport = serverTransport({
        gatewayUrl: "http://gateway.test",
        tokens: anonymousAccessTokens,
        fetch: (request) => {
            requests.push(request);
            return Promise.resolve(reply());
        },
    });
    repository = httpInviteRepository(createApiClients(transport).orgTeam);
});

describe("httpInviteRepository.list", () => {
    it("asks for one status a page at a time, newest first", async () => {
        reply = () => json(200, pageOf([inviteDto], { size: 100 }));

        const page = await repository.list(orgId, ALL_PENDING_QUERY);

        const url = new URL(requests[0]?.url ?? "");
        expect(url.pathname).toBe("/api/v1/org-team/orgs/org-1/invites");
        expect(Object.fromEntries(url.searchParams)).toEqual({
            page: "0",
            size: "100",
            sort: "createdAt,desc",
            status: "PENDING",
        });
        expect(page.items).toEqual([inviteDto]);
    });

    it("leaves the status out to ask for every invite", async () => {
        reply = () => json(200, pageOf([]));

        await repository.list(orgId, RECENT_INVITES_QUERY);

        expect(new URL(requests[0]?.url ?? "").searchParams.has("status")).toBe(false);
    });

    it("refuses a row whose status the client doesn't know", async () => {
        reply = () => json(200, pageOf([{ ...inviteDto, status: "BOUNCED" }]));

        await expect(repository.list(orgId, ALL_PENDING_QUERY)).rejects.toBeInstanceOf(UnexpectedResponseError);
    });

    it("refuses a row that carries no id", async () => {
        reply = () => json(200, pageOf([{ ...inviteDto, id: "" }]));

        await expect(repository.list(orgId, ALL_PENDING_QUERY)).rejects.toBeInstanceOf(UnexpectedResponseError);
    });
});

describe("httpInviteRepository.create", () => {
    it("posts the address and role and reads the invite back", async () => {
        reply = () => json(201, { success: true, message: "Invite created", data: inviteDto });

        expect(await repository.create(orgId, { email: inviteDto.email, role: "DEVELOPER" })).toEqual(inviteDto);
        expect(requests[0]?.method).toBe("POST");
        expect(requests[0]?.url).toBe("http://gateway.test/api/v1/org-team/orgs/org-1/invites");
        expect(await requests[0]?.json()).toEqual({ email: inviteDto.email, role: "DEVELOPER" });
    });

    it.each([
        "ALREADY_A_MEMBER",
        "MEMBER_PREVIOUSLY_REMOVED",
        "INVITE_ALREADY_PENDING",
        "QUOTA_EXCEEDED",
        "PERSONAL_ORG_IMMUTABLE",
    ])("raises %s as the backend reports it", async (code) => {
        reply = () => conflict(code);

        await expect(repository.create(orgId, { email: inviteDto.email, role: "VIEWER" })).rejects.toMatchObject({
            constructor: ApiError,
            code,
        });
    });
});

describe("httpInviteRepository.resend", () => {
    it("posts the resend and answers with the new expiry and count", async () => {
        const resent = { ...inviteDto, sendCount: 3, expiresAt: "2026-01-18T12:00:00Z" };
        reply = () => json(200, { success: true, message: "Invite resent", data: resent });

        expect(await repository.resend(orgId, inviteId)).toEqual(resent);
        expect(requests[0]?.method).toBe("POST");
        expect(requests[0]?.url).toBe(`http://gateway.test/api/v1/org-team/orgs/org-1/invites/${inviteId}/resend`);
    });

    it("raises the cooldown with how long is left", async () => {
        reply = () =>
            new Response(
                JSON.stringify({
                    success: false,
                    message: "This invitation was sent recently. Try again shortly.",
                    error: "TOO_MANY_REQUESTS",
                    statusCode: 429,
                }),
                { status: 429, headers: { "Content-Type": "application/json", "Retry-After": "180" } },
            );

        const failure = await repository.resend(orgId, inviteId).catch((error: unknown) => error);

        expect(failure).toBeInstanceOf(ApiError);
        expect((failure as ApiError).retryAfterSeconds()).toBe(180);
    });
});

describe("httpInviteRepository.revoke", () => {
    it("deletes the invite and expects no content", async () => {
        reply = () => new Response(null, { status: 204 });

        await repository.revoke(orgId, inviteId);

        expect(requests[0]?.method).toBe("DELETE");
        expect(requests[0]?.url).toBe(`http://gateway.test/api/v1/org-team/orgs/org-1/invites/${inviteId}`);
    });

    it("raises INVITE_NOT_PENDING as the backend reports it", async () => {
        reply = () => conflict("INVITE_NOT_PENDING");

        await expect(repository.revoke(orgId, inviteId)).rejects.toMatchObject({ code: "INVITE_NOT_PENDING" });
    });
});
