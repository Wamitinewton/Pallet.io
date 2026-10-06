import { anonymousAccessTokens } from "@/shared/application/access-token";
import { ApiError, UnexpectedResponseError } from "@/shared/domain/errors";
import { createApiClients } from "@/shared/infrastructure/api/clients";
import { serverTransport } from "@/shared/infrastructure/api/server-transport";
import { beforeEach, describe, expect, it } from "vitest";
import type { GitHubSessionRepository } from "../application/ports";
import { httpGitHubSessionRepository } from "./http-github-session-repository";

const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const ok = (data: unknown, status = 200) => json(status, { success: true, message: "OK", data });
const failure = (status: number, code: string) =>
    json(status, { success: false, message: "Refused", error: code, statusCode: status });

const GITHUB_URL = "http://gateway.test/api/v1/git-integration/github";
const sessionDto = { githubLogin: "amani-otieno", expiresAt: "2026-01-15T13:00:00Z" };

let requests: Request[];
let reply: () => Response;
let repository: GitHubSessionRepository;

beforeEach(() => {
    requests = [];
    reply = () => ok(sessionDto);
    const transport = serverTransport({
        gatewayUrl: "http://gateway.test",
        tokens: anonymousAccessTokens,
        fetch: (request) => {
            requests.push(request);
            return Promise.resolve(reply());
        },
    });
    repository = httpGitHubSessionRepository(createApiClients(transport).gitIntegration);
});

describe("httpGitHubSessionRepository.startAuthorization", () => {
    it("starts an authorization and hands back where to send the browser", async () => {
        const authorizeUrl = "https://github.com/login/oauth/authorize?client_id=x&state=signed";
        reply = () => ok({ authorizeUrl, expiresAt: "2026-01-15T12:10:00Z" }, 201);

        expect(await repository.startAuthorization()).toEqual({ url: authorizeUrl, expiresAt: "2026-01-15T12:10:00Z" });
        expect(requests[0]?.method).toBe("POST");
        expect(requests[0]?.url).toBe(`${GITHUB_URL}/authorizations`);
    });

    it.each(["javascript:alert(1)", "http://github.com/login/oauth/authorize", "/login"])(
        "refuses to send the browser to %s",
        async (authorizeUrl) => {
            reply = () => ok({ authorizeUrl, expiresAt: "2026-01-15T12:10:00Z" }, 201);

            await expect(repository.startAuthorization()).rejects.toBeInstanceOf(UnexpectedResponseError);
        },
    );
});

describe("httpGitHubSessionRepository.completeAuthorization", () => {
    it("sends code and state in the body, and nowhere else", async () => {
        expect(await repository.completeAuthorization({ code: "c0de", state: "st4te" })).toEqual(sessionDto);
        expect(requests[0]?.url).toBe(`${GITHUB_URL}/authorizations/complete`);
        expect(await requests[0]?.json()).toEqual({ code: "c0de", state: "st4te" });
    });

    it("raises INVALID_AUTHORIZATION_STATE as the service reports it", async () => {
        reply = () => failure(400, "INVALID_AUTHORIZATION_STATE");

        await expect(repository.completeAuthorization({ code: "c", state: "s" })).rejects.toMatchObject({
            constructor: ApiError,
            code: "INVALID_AUTHORIZATION_STATE",
        });
    });
});

describe("httpGitHubSessionRepository.find", () => {
    it("reads the caller's session", async () => {
        expect(await repository.find()).toEqual(sessionDto);
        expect(requests[0]?.url).toBe(`${GITHUB_URL}/session`);
    });

    it("reads GITHUB_SESSION_NOT_FOUND as no session", async () => {
        reply = () => failure(404, "GITHUB_SESSION_NOT_FOUND");

        expect(await repository.find()).toBeNull();
    });

    it("lets any other failure through", async () => {
        reply = () => failure(404, "ROUTE_NOT_FOUND");

        await expect(repository.find()).rejects.toMatchObject({ code: "ROUTE_NOT_FOUND" });
    });
});

describe("httpGitHubSessionRepository.end", () => {
    it("ends the session and expects no content", async () => {
        reply = () => new Response(null, { status: 204 });

        await repository.end();

        expect(requests[0]?.method).toBe("DELETE");
        expect(requests[0]?.url).toBe(`${GITHUB_URL}/session`);
    });
});

describe("httpGitHubSessionRepository.visibleInstallations", () => {
    it("reads one page of what the caller's GitHub user can see", async () => {
        reply = () =>
            ok({
                content: [
                    {
                        installationId: 41000001,
                        accountLogin: "kilima-labs",
                        accountType: "Organization",
                        suspended: false,
                    },
                    { installationId: 41000003, accountLogin: "wanjiru-kamau", accountType: "User", suspended: true },
                ],
                page: 0,
                size: 100,
                totalElements: 2,
                totalPages: 1,
                first: true,
                last: true,
            });

        const page = await repository.visibleInstallations({ page: 0, size: 100 });

        expect(page.items.map(({ accountLogin, suspended }) => [accountLogin, suspended])).toEqual([
            ["kilima-labs", false],
            ["wanjiru-kamau", true],
        ]);
        expect(Object.fromEntries(new URL(requests[0]?.url ?? "").searchParams)).toEqual({ page: "0", size: "100" });
    });

    it("raises GITHUB_AUTHORIZATION_REQUIRED without a session", async () => {
        reply = () => failure(403, "GITHUB_AUTHORIZATION_REQUIRED");

        await expect(repository.visibleInstallations({ page: 0, size: 100 })).rejects.toMatchObject({
            code: "GITHUB_AUTHORIZATION_REQUIRED",
        });
    });
});
