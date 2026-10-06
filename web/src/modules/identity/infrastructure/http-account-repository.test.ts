import { anonymousAccessTokens } from "@/shared/application/access-token";
import { ApiError, UnexpectedResponseError } from "@/shared/domain/errors";
import { createApiClients } from "@/shared/infrastructure/api/clients";
import { serverTransport } from "@/shared/infrastructure/api/server-transport";
import { beforeEach, describe, expect, it } from "vitest";
import type { AccountRepository } from "../application/ports";
import { asAccountSessionId } from "../domain/account-session";
import { httpAccountRepository } from "./http-account-repository";

const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const ok = (data: unknown) => json(200, { success: true, message: "OK", data });

let requests: Request[];
let reply: () => Response;
let repository: AccountRepository;

beforeEach(() => {
    requests = [];
    reply = () => ok(null);
    const transport = serverTransport({
        gatewayUrl: "http://gateway.test",
        tokens: anonymousAccessTokens,
        fetch: (request) => {
            requests.push(request);
            return Promise.resolve(reply());
        },
    });
    repository = httpAccountRepository(createApiClients(transport).identity);
});

describe("httpAccountRepository.getMyProfile", () => {
    it("maps the profile and leaves the token's org and roles behind", async () => {
        reply = () =>
            ok({
                sub: "user-1",
                email: "amani@kilimalabs.co",
                displayName: "Amani Otieno",
                status: "ACTIVE",
                orgId: "org-1",
                roles: ["owner"],
            });

        expect(await repository.getMyProfile()).toEqual({
            userId: "user-1",
            email: "amani@kilimalabs.co",
            displayName: "Amani Otieno",
            status: "ACTIVE",
        });
        expect(requests[0]?.url).toBe("http://gateway.test/api/v1/identity/users/me");
    });

    it("refuses a profile without a subject", async () => {
        reply = () => ok({ email: "amani@kilimalabs.co", displayName: "Amani", status: "ACTIVE" });

        await expect(repository.getMyProfile()).rejects.toBeInstanceOf(UnexpectedResponseError);
    });
});

describe("httpAccountRepository.updateProfile", () => {
    it("patches the display name", async () => {
        await repository.updateProfile({ displayName: "Amani O." });

        expect(requests[0]?.method).toBe("PATCH");
        expect(requests[0]?.url).toBe("http://gateway.test/api/v1/identity/users/me");
        expect(await requests[0]?.json()).toEqual({ displayName: "Amani O." });
    });
});

describe("httpAccountRepository.changePassword", () => {
    it("posts the current and the new password", async () => {
        await repository.changePassword({
            currentPassword: "old-river-crossing",
            newPassword: "mango-season-in-kisumu",
        });

        expect(requests[0]?.url).toBe("http://gateway.test/api/v1/identity/users/me/password");
        expect(await requests[0]?.json()).toEqual({
            currentPassword: "old-river-crossing",
            newPassword: "mango-season-in-kisumu",
        });
    });

    it("raises a wrong current password as INVALID_CREDENTIALS", async () => {
        reply = () =>
            json(401, {
                success: false,
                message: "Invalid credentials.",
                error: "INVALID_CREDENTIALS",
                statusCode: 401,
            });

        await expect(
            repository.changePassword({ currentPassword: "wrong-password!", newPassword: "mango-season-in-kisumu" }),
        ).rejects.toMatchObject({ constructor: ApiError, status: 401, code: "INVALID_CREDENTIALS" });
    });
});

describe("httpAccountRepository.listSessions", () => {
    it("maps each session and drops a blank address", async () => {
        reply = () =>
            ok([
                {
                    id: "kc-1",
                    ipAddress: "197.237.14.82",
                    startedAt: "2026-01-15T08:41:00.123Z",
                    lastAccessedAt: "2026-01-15T11:59:00Z",
                },
                {
                    id: "kc-2",
                    ipAddress: null,
                    startedAt: "2026-01-09T10:15:00Z",
                    lastAccessedAt: "2026-01-09T10:15:00Z",
                },
            ]);

        expect(await repository.listSessions()).toEqual([
            {
                id: "kc-1",
                ipAddress: "197.237.14.82",
                startedAt: "2026-01-15T08:41:00.123Z",
                lastAccessedAt: "2026-01-15T11:59:00Z",
            },
            {
                id: "kc-2",
                ipAddress: undefined,
                startedAt: "2026-01-09T10:15:00Z",
                lastAccessedAt: "2026-01-09T10:15:00Z",
            },
        ]);
        expect(requests[0]?.url).toBe("http://gateway.test/api/v1/identity/users/me/sessions");
    });

    it("refuses a session without an id", async () => {
        reply = () => ok([{ startedAt: "2026-01-09T10:15:00Z", lastAccessedAt: "2026-01-09T10:15:00Z" }]);

        await expect(repository.listSessions()).rejects.toBeInstanceOf(UnexpectedResponseError);
    });
});

describe("httpAccountRepository.revokeSession", () => {
    it("deletes the named session", async () => {
        await repository.revokeSession(asAccountSessionId("3f1c2a9e-77b4-4d1a-9a35-0d3b1e6f2c11"));

        expect(requests[0]?.method).toBe("DELETE");
        expect(requests[0]?.url).toBe(
            "http://gateway.test/api/v1/identity/users/me/sessions/3f1c2a9e-77b4-4d1a-9a35-0d3b1e6f2c11",
        );
    });

    it("raises SESSION_NOT_FOUND as an ApiError", async () => {
        reply = () =>
            json(404, { success: false, message: "Session not found.", error: "SESSION_NOT_FOUND", statusCode: 404 });

        await expect(repository.revokeSession(asAccountSessionId("kc-gone"))).rejects.toMatchObject({
            constructor: ApiError,
            code: "SESSION_NOT_FOUND",
        });
    });
});

describe("httpAccountRepository.revokeOtherSessions", () => {
    it("deletes the session collection", async () => {
        await repository.revokeOtherSessions();

        expect(requests[0]?.method).toBe("DELETE");
        expect(requests[0]?.url).toBe("http://gateway.test/api/v1/identity/users/me/sessions");
    });
});
