import { anonymousAccessTokens } from "@/shared/application/access-token";
import { ApiError, UnexpectedResponseError } from "@/shared/domain/errors";
import { asInstallationId, asOrgId, asRepoId } from "@/shared/domain/ids";
import { createApiClients } from "@/shared/infrastructure/api/clients";
import { serverTransport } from "@/shared/infrastructure/api/server-transport";
import { disconnectedRepoLinkDto, repoLinkDto } from "@/test/msw/git-integration";
import { beforeEach, describe, expect, it } from "vitest";
import type { RepoLinkRepository } from "../application/ports";
import { aDisconnectedLink, aRepoLink, CHECKOUT_APP_ID } from "../domain/testing/fixtures";
import { httpRepoLinkRepository } from "./http-repo-link-repository";

const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const ok = (data: unknown, status = 200) => json(status, { success: true, message: "OK", data });
const failure = (status: number, code: string) =>
    json(status, { success: false, message: "Refused", error: code, statusCode: status });

const LINK_URL = `http://gateway.test/api/v1/git-integration/orgs/org-1/apps/${CHECKOUT_APP_ID}/repo-link`;
const orgId = asOrgId("org-1");
const appId = CHECKOUT_APP_ID;

let requests: Request[];
let reply: () => Response;
let repository: RepoLinkRepository;

beforeEach(() => {
    requests = [];
    reply = () => ok(repoLinkDto());
    const transport = serverTransport({
        gatewayUrl: "http://gateway.test",
        tokens: anonymousAccessTokens,
        fetch: (request) => {
            requests.push(request);
            return Promise.resolve(reply());
        },
    });
    repository = httpRepoLinkRepository(createApiClients(transport).gitIntegration);
});

describe("httpRepoLinkRepository.find", () => {
    it("maps an active link", async () => {
        expect(await repository.find(orgId, appId)).toEqual(aRepoLink());
        expect(requests[0]?.url).toBe(LINK_URL);
    });

    it("maps a disconnected link with its reason and time", async () => {
        reply = () => ok(disconnectedRepoLinkDto("REPOSITORY_ACCESS_REMOVED"));

        expect(await repository.find(orgId, appId)).toEqual(aDisconnectedLink("REPOSITORY_ACCESS_REMOVED"));
    });

    it("reads a null root directory as the repository root", async () => {
        reply = () => ok({ ...repoLinkDto(), rootDirectory: null });

        expect((await repository.find(orgId, appId))?.rootDirectory).toBe("");
    });

    it("keeps the warnings it knows and leaves out the ones it doesn't", async () => {
        reply = () => ok(repoLinkDto({ warnings: ["REPOSITORY_ARCHIVED", "SOMETHING_NEW"] }));

        expect((await repository.find(orgId, appId))?.warnings).toEqual(["REPOSITORY_ARCHIVED"]);
    });

    it("reads no link yet as null", async () => {
        reply = () => failure(404, "REPO_LINK_NOT_FOUND");

        expect(await repository.find(orgId, appId)).toBeNull();
    });

    it("lets a missing app through as the error it is", async () => {
        reply = () => failure(404, "APP_NOT_FOUND");

        await expect(repository.find(orgId, appId)).rejects.toBeInstanceOf(ApiError);
    });

    it("refuses a disconnected link that doesn't say why", async () => {
        reply = () => ok(repoLinkDto({ status: "DISCONNECTED" }));

        await expect(repository.find(orgId, appId)).rejects.toBeInstanceOf(UnexpectedResponseError);
    });

    it("refuses a disconnect reason it has never heard of, rather than guess what to show", async () => {
        reply = () => ok(disconnectedRepoLinkDto("STOLEN_BY_PIRATES"));

        await expect(repository.find(orgId, appId)).rejects.toBeInstanceOf(UnexpectedResponseError);
    });
});

describe("httpRepoLinkRepository.link", () => {
    const request = {
        installationId: asInstallationId(41000001),
        repoId: asRepoId(72000001),
        settings: { productionBranch: "main", rootDirectory: "", autoDeploy: true },
        deployNow: true,
    };

    it("puts the link, leaving out a root directory that is the repository root", async () => {
        reply = () => ok(repoLinkDto({ version: 0 }), 201);

        expect(await repository.link(orgId, appId, request)).toMatchObject({ version: 0 });
        expect(requests[0]?.method).toBe("PUT");
        expect(await requests[0]?.json()).toEqual({
            installationId: 41000001,
            repoId: 72000001,
            productionBranch: "main",
            autoDeploy: true,
            deployNow: true,
        });
    });

    it("sends a subdirectory", async () => {
        reply = () => ok(repoLinkDto(), 201);

        await repository.link(orgId, appId, {
            ...request,
            settings: { ...request.settings, rootDirectory: "apps/web" },
        });

        expect(await requests[0]?.json()).toMatchObject({ rootDirectory: "apps/web" });
    });
});

describe("httpRepoLinkRepository.tune", () => {
    it("patches exactly the changed fields and the version, never an id", async () => {
        await repository.tune(orgId, appId, { rootDirectory: "" }, 3);

        expect(requests[0]?.method).toBe("PATCH");
        expect(await requests[0]?.text()).toBe('{"rootDirectory":"","version":3}');
    });
});

describe("httpRepoLinkRepository.takeOverVerification", () => {
    it("posts to the link's verification", async () => {
        await repository.takeOverVerification(orgId, appId);

        expect(requests[0]?.method).toBe("POST");
        expect(requests[0]?.url).toBe(`${LINK_URL}/verification`);
    });
});

describe("httpRepoLinkRepository.disconnect", () => {
    it("deletes the link and expects nothing back", async () => {
        reply = () => new Response(null, { status: 204 });

        await repository.disconnect(orgId, appId);

        expect(requests[0]?.method).toBe("DELETE");
    });
});

describe("httpRepoLinkRepository.requestBuild", () => {
    const build = { eventId: "0d6c1a2b-3c4d-4e5f-8a6b-000000000001", commitSha: "3f9c2e1", branch: "main" };

    it("sends the intent's key as Idempotency-Key", async () => {
        reply = () => ok(build, 202);

        expect(await repository.requestBuild(orgId, appId, {}, "intent-1")).toEqual(build);
        expect(requests[0]?.url).toBe(`${LINK_URL}/builds`);
        expect(requests[0]?.headers.get("Idempotency-Key")).toBe("intent-1");
        expect(await requests[0]?.text()).toBe("{}");
    });

    it("names a branch when one was asked for", async () => {
        reply = () => ok({ ...build, branch: "release" }, 202);

        await repository.requestBuild(orgId, appId, { branch: "release" }, "intent-2");

        expect(await requests[0]?.json()).toEqual({ branch: "release" });
    });
});
