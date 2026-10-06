import { anonymousAccessTokens } from "@/shared/application/access-token";
import { ApiError, UnexpectedResponseError } from "@/shared/domain/errors";
import { asAppId, asOrgId, asTeamId } from "@/shared/domain/ids";
import { createApiClients } from "@/shared/infrastructure/api/clients";
import { serverTransport } from "@/shared/infrastructure/api/server-transport";
import { beforeEach, describe, expect, it } from "vitest";
import type { AppRepository } from "../application/ports";
import { appListQuery, DEFAULT_APP_LIST_PARAMS, NO_TEAM_PARAM } from "../domain/app-list-query";
import { httpAppRepository } from "./http-app-repository";

const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const ok = (data: unknown, message = "OK") => json(200, { success: true, message, data });
const failure = (status: number, code: string) =>
    json(status, { success: false, message: "Refused", error: code, statusCode: status });

const APP_ID = "6f1c2a9e-4b7d-4e21-9a0c-3d5b8e7f1a24";
const TEAM_ID = "3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b";
const APPS_URL = "http://gateway.test/api/v1/org-team/orgs/org-1/apps";

const appDto = {
    id: APP_ID,
    name: "Checkout API",
    slug: "checkout-api",
    cloudProvider: "AWS",
    region: "af-south-1",
    teamId: TEAM_ID,
    createdAt: "2026-01-09T09:00:00Z",
    updatedAt: "2026-01-12T09:00:00Z",
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
const appId = asAppId(APP_ID);

let requests: Request[];
let reply: () => Response;
let repository: AppRepository;

beforeEach(() => {
    requests = [];
    reply = () => ok(appDto);
    const transport = serverTransport({
        gatewayUrl: "http://gateway.test",
        tokens: anonymousAccessTokens,
        fetch: (request) => {
            requests.push(request);
            return Promise.resolve(reply());
        },
    });
    repository = httpAppRepository(createApiClients(transport).orgTeam);
});

const lastUrl = () => new URL(requests.at(-1)?.url ?? "");

describe("httpAppRepository.list", () => {
    it("asks for one unfiltered page in the backend's default order", async () => {
        reply = () => pageOf([appDto]);

        const page = await repository.list(orgId, appListQuery(DEFAULT_APP_LIST_PARAMS));

        expect(lastUrl().pathname).toBe("/api/v1/org-team/orgs/org-1/apps");
        expect(Object.fromEntries(lastUrl().searchParams)).toEqual({ page: "0", size: "20", sort: "createdAt,desc" });
        expect(page.items).toEqual([appDto]);
    });

    it("sends the search, team and provider filters", async () => {
        reply = () => pageOf([], { page: 1, size: 20, totalElements: 21 });

        await repository.list(
            orgId,
            appListQuery({
                q: "  check ",
                team: TEAM_ID,
                cloud: "GCP",
                sort: { field: "name", direction: "asc" },
                page: 2,
            }),
        );

        expect(Object.fromEntries(lastUrl().searchParams)).toEqual({
            page: "1",
            size: "20",
            sort: "name,asc",
            teamId: TEAM_ID,
            cloudProvider: "GCP",
            q: "check",
        });
    });

    it("asks for apps without a team with unassigned, never with a teamId", async () => {
        reply = () => pageOf([]);

        await repository.list(orgId, appListQuery({ ...DEFAULT_APP_LIST_PARAMS, team: NO_TEAM_PARAM }));

        expect(Object.fromEntries(lastUrl().searchParams)).toEqual({
            page: "0",
            size: "20",
            sort: "createdAt,desc",
            unassigned: "true",
        });
    });

    it("reads an app without a team, whether the field is null or left out", async () => {
        const withoutTeam: Partial<typeof appDto> = { ...appDto };
        delete withoutTeam.teamId;
        reply = () => pageOf([{ ...appDto, teamId: null }, withoutTeam]);

        const page = await repository.list(orgId, appListQuery(DEFAULT_APP_LIST_PARAMS));

        expect(page.items.map((app) => app.teamId)).toEqual([null, null]);
    });

    it("refuses a provider outside the contract", async () => {
        reply = () => pageOf([{ ...appDto, cloudProvider: "AZURE" }]);

        await expect(repository.list(orgId, appListQuery(DEFAULT_APP_LIST_PARAMS))).rejects.toBeInstanceOf(
            UnexpectedResponseError,
        );
    });
});

describe("httpAppRepository.get", () => {
    it("reads one app", async () => {
        expect(await repository.get(orgId, appId)).toEqual(appDto);
        expect(requests[0]?.url).toBe(`${APPS_URL}/${APP_ID}`);
    });

    it("keeps a region the catalog no longer lists", async () => {
        reply = () => ok({ ...appDto, region: "sa-east-1" });

        expect((await repository.get(orgId, appId)).region).toBe("sa-east-1");
    });

    it("raises APP_NOT_FOUND as the backend reports it", async () => {
        reply = () => failure(404, "APP_NOT_FOUND");

        await expect(repository.get(orgId, appId)).rejects.toMatchObject({
            constructor: ApiError,
            code: "APP_NOT_FOUND",
        });
    });
});

describe("httpAppRepository.create", () => {
    const created = () => json(201, { success: true, message: "App created", data: appDto });

    it("sends every chosen field", async () => {
        reply = created;

        expect(
            await repository.create(orgId, {
                name: "Checkout API",
                slug: "checkout-api",
                cloudProvider: "AWS",
                region: "af-south-1",
                teamId: asTeamId(TEAM_ID),
            }),
        ).toEqual(appDto);
        expect(requests[0]?.method).toBe("POST");
        expect(requests[0]?.url).toBe(APPS_URL);
        expect(await requests[0]?.json()).toEqual({
            name: "Checkout API",
            slug: "checkout-api",
            cloudProvider: "AWS",
            region: "af-south-1",
            teamId: TEAM_ID,
        });
    });

    it("leaves the slug and the team out when none was chosen", async () => {
        reply = created;

        await repository.create(orgId, {
            name: "Checkout API",
            slug: undefined,
            cloudProvider: "GCP",
            region: "europe-west1",
            teamId: null,
        });

        expect(await requests[0]?.json()).toEqual({
            name: "Checkout API",
            cloudProvider: "GCP",
            region: "europe-west1",
        });
    });

    it.each([
        [400, "INVALID_REGION"],
        [409, "SLUG_TAKEN"],
        [409, "QUOTA_EXCEEDED"],
    ])("raises %s %s as the backend reports it", async (status, code) => {
        reply = () => failure(status, code);

        await expect(
            repository.create(orgId, {
                name: "Checkout API",
                slug: undefined,
                cloudProvider: "AWS",
                region: "eu-west-1",
                teamId: null,
            }),
        ).rejects.toMatchObject({ code });
    });
});

describe("httpAppRepository.update", () => {
    it("sends the name alone, with no teamId key", async () => {
        reply = () => ok({ ...appDto, name: "Checkout" }, "App updated");

        expect((await repository.update(orgId, appId, { name: "Checkout" })).name).toBe("Checkout");
        expect(requests[0]?.method).toBe("PATCH");
        expect(await requests[0]?.text()).toBe('{"name":"Checkout"}');
    });

    it("detaches with an explicit null", async () => {
        reply = () => ok({ ...appDto, teamId: null }, "App updated");

        expect((await repository.update(orgId, appId, { teamId: null })).teamId).toBeNull();
        expect(await requests[0]?.text()).toBe('{"teamId":null}');
    });

    it("raises CONCURRENT_MODIFICATION as the backend reports it", async () => {
        reply = () => failure(409, "CONCURRENT_MODIFICATION");

        await expect(repository.update(orgId, appId, { name: "Checkout" })).rejects.toMatchObject({
            code: "CONCURRENT_MODIFICATION",
        });
    });
});

describe("httpAppRepository.delete", () => {
    it("deletes the app and expects no content", async () => {
        reply = () => new Response(null, { status: 204 });

        await repository.delete(orgId, appId);

        expect(requests[0]?.method).toBe("DELETE");
        expect(requests[0]?.url).toBe(`${APPS_URL}/${APP_ID}`);
    });

    it("refuses a body where no content was promised", async () => {
        reply = () => ok(null);

        await expect(repository.delete(orgId, appId)).rejects.toBeInstanceOf(UnexpectedResponseError);
    });
});
