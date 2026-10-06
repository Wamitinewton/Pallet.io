import { anonymousAccessTokens } from "@/shared/application/access-token";
import { ApiError, UnexpectedResponseError } from "@/shared/domain/errors";
import { createApiClients } from "@/shared/infrastructure/api/clients";
import { serverTransport } from "@/shared/infrastructure/api/server-transport";
import { beforeEach, describe, expect, it } from "vitest";
import type { InviteAcceptanceRepository } from "../application/ports";
import { anInviteToken } from "../domain/testing/fixtures";
import { httpInviteAcceptanceRepository } from "./http-invite-acceptance-repository";

const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const previewDto = {
    orgName: "Kilima Labs",
    role: "DEVELOPER",
    inviterName: "Grace Njeri",
    maskedEmail: "d***@kilimalabs.co",
    expiresAt: "2026-01-17T12:00:00Z",
};

const accepted = () => json(201, { success: true, message: "Invite accepted", data: null });

const token = anInviteToken();
const PASSWORD = "mango-season-in-kisumu";

let requests: Request[];
let reply: () => Response;
let repository: InviteAcceptanceRepository;

beforeEach(() => {
    requests = [];
    reply = () => json(200, { success: true, message: "Invite retrieved", data: previewDto });
    const transport = serverTransport({
        gatewayUrl: "http://gateway.test",
        tokens: anonymousAccessTokens,
        fetch: (request) => {
            requests.push(request);
            return Promise.resolve(reply());
        },
    });
    repository = httpInviteAcceptanceRepository(createApiClients(transport));
});

describe("httpInviteAcceptanceRepository", () => {
    it("reads the public preview from org-team-service", async () => {
        expect(await repository.preview(token)).toEqual(previewDto);
        expect(requests[0]?.method).toBe("GET");
        expect(requests[0]?.url).toBe(`http://gateway.test/api/v1/org-team/invites/${token}`);
        expect(requests[0]?.headers.has("Authorization")).toBe(false);
    });

    it("raises a withdrawn invite as an ApiError with its code", async () => {
        reply = () => json(410, { success: false, message: "Gone", error: "INVITE_NO_LONGER_VALID", statusCode: 410 });

        await expect(repository.preview(token)).rejects.toMatchObject({
            constructor: ApiError,
            status: 410,
            code: "INVITE_NO_LONGER_VALID",
        });
    });

    it("refuses a preview that breaks its contract", async () => {
        reply = () => json(200, { success: true, message: "Invite retrieved", data: { ...previewDto, role: "GUEST" } });

        await expect(repository.preview(token)).rejects.toBeInstanceOf(UnexpectedResponseError);
    });

    it("creates an account with the password and nothing else", async () => {
        reply = accepted;

        await repository.acceptWithNewAccount(token, PASSWORD);

        expect(requests[0]?.method).toBe("POST");
        expect(requests[0]?.url).toBe(`http://gateway.test/api/v1/identity/invites/${token}/accept`);
        expect(await requests[0]?.json()).toEqual({ password: PASSWORD });
    });

    it("accepts as the signed-in account without a password", async () => {
        reply = accepted;

        await repository.acceptAsSignedIn(token);

        expect(requests[0]?.url).toBe(`http://gateway.test/api/v1/identity/invites/${token}/accept`);
        expect(await requests[0]?.json()).toEqual({});
    });

    it("raises a taken address as CONFLICT", async () => {
        reply = () => json(409, { success: false, message: "Conflict", error: "CONFLICT", statusCode: 409 });

        await expect(repository.acceptWithNewAccount(token, PASSWORD)).rejects.toMatchObject({
            status: 409,
            code: "CONFLICT",
        });
    });
});
