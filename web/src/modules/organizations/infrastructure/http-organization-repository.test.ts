import { anonymousAccessTokens } from "@/shared/application/access-token";
import { ApiError, UnexpectedResponseError } from "@/shared/domain/errors";
import { asOrgId } from "@/shared/domain/ids";
import { createApiClients } from "@/shared/infrastructure/api/clients";
import { serverTransport } from "@/shared/infrastructure/api/server-transport";
import { beforeEach, describe, expect, it } from "vitest";
import type { OrganizationRepository } from "../application/ports";
import { httpOrganizationRepository } from "./http-organization-repository";

const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const ok = (data: unknown) => json(200, { success: true, message: "OK", data });

const summaryDto = { orgId: "org-1", name: "Kilima Labs", slug: "kilima-labs", kind: "TEAM", myRole: "ADMIN" };

const organizationDto = {
    orgId: "org-1",
    name: "Kilima Labs",
    slug: "kilima-labs",
    kind: "TEAM",
    status: "ACTIVE",
    ownerUserId: "user-1",
    createdAt: "2026-01-02T09:00:00.123456Z",
    counts: { members: 6, teams: 3, apps: 4 },
};

let requests: Request[];
let reply: (request: Request) => Response;
let repository: OrganizationRepository;

beforeEach(() => {
    requests = [];
    reply = () => ok(null);
    const transport = serverTransport({
        gatewayUrl: "http://gateway.test",
        tokens: anonymousAccessTokens,
        fetch: (request) => {
            requests.push(request);
            return Promise.resolve(reply(request));
        },
    });
    repository = httpOrganizationRepository(createApiClients(transport).orgTeam);
});

describe("httpOrganizationRepository.listMine", () => {
    it("asks for the page and maps each summary", async () => {
        reply = () =>
            ok({
                content: [summaryDto],
                page: 0,
                size: 100,
                totalElements: 1,
                totalPages: 1,
                first: true,
                last: true,
            });

        const page = await repository.listMine({ page: 0, size: 100 });

        expect(requests[0]?.url).toBe("http://gateway.test/api/v1/org-team/orgs?page=0&size=100");
        expect(page.items).toEqual([summaryDto]);
        expect(page.isLast).toBe(true);
    });

    it("refuses a role the client does not know", async () => {
        reply = () =>
            ok({
                content: [{ ...summaryDto, myRole: "SUPERUSER" }],
                page: 0,
                size: 100,
                totalElements: 1,
                totalPages: 1,
                first: true,
                last: true,
            });

        await expect(repository.listMine({ page: 0, size: 100 })).rejects.toBeInstanceOf(UnexpectedResponseError);
    });
});

describe("httpOrganizationRepository.get", () => {
    it("reads the organization with its counts", async () => {
        reply = () => ok(organizationDto);

        expect(await repository.get(asOrgId("org-1"))).toEqual(organizationDto);
        expect(requests[0]?.url).toBe("http://gateway.test/api/v1/org-team/orgs/org-1");
    });

    it("raises another organization's id as the backend reports it", async () => {
        reply = () =>
            json(404, { success: false, message: "Organization not found.", error: "ORG_NOT_FOUND", statusCode: 404 });

        await expect(repository.get(asOrgId("org-elsewhere"))).rejects.toMatchObject({
            constructor: ApiError,
            code: "ORG_NOT_FOUND",
        });
    });
});

describe("httpOrganizationRepository.create", () => {
    it("posts the name and slug and maps the new organization", async () => {
        reply = () => json(201, { success: true, message: "Organization created", data: organizationDto });

        const created = await repository.create({ name: "Kilima Labs", slug: "kilima-labs" });

        expect(requests[0]?.method).toBe("POST");
        expect(requests[0]?.url).toBe("http://gateway.test/api/v1/org-team/orgs");
        expect(await requests[0]?.json()).toEqual({ name: "Kilima Labs", slug: "kilima-labs" });
        expect(created).toEqual(organizationDto);
    });

    it("leaves the slug out of the body when the backend is to derive it", async () => {
        reply = () => json(201, { success: true, message: "Organization created", data: organizationDto });

        await repository.create({ name: "Kilima Labs", slug: undefined });

        expect(await requests[0]?.json()).toEqual({ name: "Kilima Labs" });
    });

    it("raises a taken slug as the backend reports it", async () => {
        reply = () => json(409, { success: false, message: "Slug taken.", error: "SLUG_TAKEN", statusCode: 409 });

        await expect(repository.create({ name: "Kilima Labs", slug: "kilima-labs" })).rejects.toMatchObject({
            constructor: ApiError,
            code: "SLUG_TAKEN",
        });
    });
});

describe("httpOrganizationRepository.rename", () => {
    it("patches only the name and maps the renamed organization", async () => {
        reply = () => ok({ ...organizationDto, name: "Kilima Cloud" });

        const renamed = await repository.rename(asOrgId("org-1"), { name: "Kilima Cloud" });

        expect(requests[0]?.method).toBe("PATCH");
        expect(requests[0]?.url).toBe("http://gateway.test/api/v1/org-team/orgs/org-1");
        expect(await requests[0]?.json()).toEqual({ name: "Kilima Cloud" });
        expect(renamed.name).toBe("Kilima Cloud");
    });
});

describe("httpOrganizationRepository.delete", () => {
    it("sends the confirmation in X-Confirm-Slug and expects no content", async () => {
        reply = () => new Response(null, { status: 204 });

        await repository.delete(asOrgId("org-1"), "kilima-labs");

        expect(requests[0]?.method).toBe("DELETE");
        expect(requests[0]?.url).toBe("http://gateway.test/api/v1/org-team/orgs/org-1");
        expect(requests[0]?.headers.get("X-Confirm-Slug")).toBe("kilima-labs");
    });

    it("raises a stale sign-in as the backend reports it", async () => {
        reply = () =>
            json(403, {
                success: false,
                message: "Sign in again to continue.",
                error: "REAUTHENTICATION_REQUIRED",
                statusCode: 403,
            });

        await expect(repository.delete(asOrgId("org-1"), "kilima-labs")).rejects.toMatchObject({
            constructor: ApiError,
            status: 403,
            code: "REAUTHENTICATION_REQUIRED",
        });
    });
});
