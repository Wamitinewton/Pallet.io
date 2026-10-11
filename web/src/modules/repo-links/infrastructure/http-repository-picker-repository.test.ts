import { anonymousAccessTokens } from "@/shared/application/access-token";
import { asInstallationId, asOrgId } from "@/shared/domain/ids";
import { createApiClients } from "@/shared/infrastructure/api/clients";
import { serverTransport } from "@/shared/infrastructure/api/server-transport";
import { pageBody } from "@/test/msw/envelopes";
import { pickerRepositoryDto } from "@/test/msw/git-integration";
import { beforeEach, describe, expect, it } from "vitest";
import type { RepositoryPickerRepository } from "../application/ports";
import { aPickerRepository } from "../domain/testing/fixtures";
import { httpRepositoryPickerRepository } from "./http-repository-picker-repository";

const REPOSITORIES_URL =
    "http://gateway.test/api/v1/git-integration/orgs/org-1/github/installations/41000001/repositories";
const orgId = asOrgId("org-1");
const installationId = asInstallationId(41000001);

let requests: Request[];
let content: unknown[];
let repository: RepositoryPickerRepository;

beforeEach(() => {
    requests = [];
    content = [pickerRepositoryDto()];
    const transport = serverTransport({
        gatewayUrl: "http://gateway.test",
        tokens: anonymousAccessTokens,
        fetch: (request) => {
            requests.push(request);
            return Promise.resolve(
                new Response(JSON.stringify({ success: true, message: "OK", data: pageBody(content) }), {
                    headers: { "Content-Type": "application/json" },
                }),
            );
        },
    });
    repository = httpRepositoryPickerRepository(createApiClients(transport).gitIntegration);
});

describe("httpRepositoryPickerRepository.list", () => {
    it("maps each repository, calling private what it is", async () => {
        const page = await repository.list(orgId, installationId, { q: "", page: 0, size: 20 });

        expect(page.items).toEqual([aPickerRepository()]);
        expect(requests[0]?.url).toBe(`${REPOSITORIES_URL}?page=0&size=20`);
    });

    it("filters by name prefix only when there is one", async () => {
        await repository.list(orgId, installationId, { q: "web", page: 1, size: 20 });

        expect(new URL(requests[0]?.url ?? "").searchParams.get("q")).toBe("web");
        expect(new URL(requests[0]?.url ?? "").searchParams.get("page")).toBe("1");
    });

    it("reads no permission at all as null", async () => {
        content = [pickerRepositoryDto({ permission: null, linkable: false })];

        expect((await repository.list(orgId, installationId, { q: "", page: 0, size: 20 })).items[0]).toMatchObject({
            permission: null,
            linkable: false,
        });
    });
});
