import { anonymousAccessTokens } from "@/shared/application/access-token";
import { UnexpectedResponseError } from "@/shared/domain/errors";
import { asInstallationId, asOrgId } from "@/shared/domain/ids";
import { createApiClients } from "@/shared/infrastructure/api/clients";
import { serverTransport } from "@/shared/infrastructure/api/server-transport";
import { beforeEach, describe, expect, it } from "vitest";
import type { InstallationRepository } from "../application/ports";
import { httpInstallationRepository } from "./http-installation-repository";

const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const ok = (data: unknown, status = 200) => json(status, { success: true, message: "OK", data });
const failure = (status: number, code: string) =>
    json(status, { success: false, message: "Refused", error: code, statusCode: status });

const ORG_URL = "http://gateway.test/api/v1/git-integration/orgs/org-1/github";
const orgId = asOrgId("org-1");
const installationId = asInstallationId(41000001);
const linkDto = {
    installationId: 41000001,
    accountLogin: "kilima-labs",
    accountType: "Organization",
    status: "ACTIVE",
    linkedByUserId: "user-amani",
    linkedAt: "2026-01-10T09:00:00Z",
};

let requests: Request[];
let reply: () => Response;
let repository: InstallationRepository;

beforeEach(() => {
    requests = [];
    reply = () => ok(linkDto, 201);
    const transport = serverTransport({
        gatewayUrl: "http://gateway.test",
        tokens: anonymousAccessTokens,
        fetch: (request) => {
            requests.push(request);
            return Promise.resolve(reply());
        },
    });
    repository = httpInstallationRepository(createApiClients(transport).gitIntegration);
});

describe("httpInstallationRepository.startInstall", () => {
    it("starts an install session for the organization", async () => {
        const installUrl = "https://github.com/apps/pallet/installations/new?state=signed";
        reply = () => ok({ installUrl, expiresAt: "2026-01-15T12:10:00Z" }, 201);

        expect(await repository.startInstall(orgId)).toEqual({ url: installUrl, expiresAt: "2026-01-15T12:10:00Z" });
        expect(requests[0]?.method).toBe("POST");
        expect(requests[0]?.url).toBe(`${ORG_URL}/install-sessions`);
    });

    it("refuses an install URL that isn't https", async () => {
        reply = () => ok({ installUrl: "javascript:void(0)", expiresAt: "2026-01-15T12:10:00Z" }, 201);

        await expect(repository.startInstall(orgId)).rejects.toBeInstanceOf(UnexpectedResponseError);
    });
});

describe("httpInstallationRepository.link", () => {
    it("links a fresh install with its id, code and state", async () => {
        const outcome = await repository.link(orgId, { installationId, grant: { code: "c0de", state: "st4te" } });

        expect(outcome).toEqual({ alreadyLinked: false, link: linkDto });
        expect(requests[0]?.url).toBe(`${ORG_URL}/installations`);
        expect(await requests[0]?.json()).toEqual({ installationId: 41000001, code: "c0de", state: "st4te" });
    });

    it("links an existing installation by its id alone", async () => {
        await repository.link(orgId, { installationId });

        expect(await requests[0]?.text()).toBe('{"installationId":41000001}');
    });

    it("reads 200 as already linked", async () => {
        reply = () => ok(linkDto, 200);

        expect((await repository.link(orgId, { installationId })).alreadyLinked).toBe(true);
    });

    it.each([
        [400, "INVALID_AUTHORIZATION_STATE"],
        [403, "INSTALLATION_NOT_ACCESSIBLE"],
        [403, "GITHUB_AUTHORIZATION_REQUIRED"],
        [409, "INSTALLATION_SUSPENDED"],
    ])("raises %s %s as the service reports it", async (status, code) => {
        reply = () => failure(status, code);

        await expect(repository.link(orgId, { installationId })).rejects.toMatchObject({ code });
    });
});

describe("httpInstallationRepository.list", () => {
    it("reads one page of the organization's installations", async () => {
        reply = () =>
            ok({
                content: [linkDto, { ...linkDto, installationId: 41000003, status: "SUSPENDED", accountType: "User" }],
                page: 0,
                size: 100,
                totalElements: 2,
                totalPages: 1,
                first: true,
                last: true,
            });

        const page = await repository.list(orgId, { page: 0, size: 100 });

        expect(page.items.map(({ installationId: id, status }) => [id, status])).toEqual([
            [41000001, "ACTIVE"],
            [41000003, "SUSPENDED"],
        ]);
        expect(requests[0]?.url).toBe(`${ORG_URL}/installations?page=0&size=100`);
    });

    it("refuses a status outside the contract", async () => {
        reply = () =>
            ok({
                content: [{ ...linkDto, status: "DELETED" }],
                page: 0,
                size: 100,
                totalElements: 1,
                totalPages: 1,
                first: true,
                last: true,
            });

        await expect(repository.list(orgId, { page: 0, size: 100 })).rejects.toBeInstanceOf(UnexpectedResponseError);
    });
});

describe("httpInstallationRepository.unlink", () => {
    it("unlinks by id and expects no content", async () => {
        reply = () => new Response(null, { status: 204 });

        await repository.unlink(orgId, installationId);

        expect(requests[0]?.method).toBe("DELETE");
        expect(requests[0]?.url).toBe(`${ORG_URL}/installations/41000001`);
    });

    it("raises INSTALLATION_NOT_FOUND as the service reports it", async () => {
        reply = () => failure(404, "INSTALLATION_NOT_FOUND");

        await expect(repository.unlink(orgId, installationId)).rejects.toMatchObject({
            code: "INSTALLATION_NOT_FOUND",
        });
    });
});
