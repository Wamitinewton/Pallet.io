import { anonymousAccessTokens } from "@/shared/application/access-token";
import { ApiError, UnexpectedResponseError } from "@/shared/domain/errors";
import { createApiClients } from "@/shared/infrastructure/api/clients";
import { serverTransport } from "@/shared/infrastructure/api/server-transport";
import { beforeEach, describe, expect, it } from "vitest";
import type { SignupRepository } from "../application/ports";
import { httpSignupRepository } from "./http-signup-repository";

const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const ok = (data: unknown, status = 200) => json(status, { success: true, message: "OK", data });

const details = {
    organizationName: "Kilima Labs",
    slug: "kilima-labs",
    displayName: "Amani Otieno",
    email: "amani@kilimalabs.co",
    password: "correct horse battery",
};

let requests: Request[];
let reply: (request: Request) => Response;
let repository: SignupRepository;

beforeEach(() => {
    requests = [];
    reply = () => ok({ orgId: "org-1", orgName: "Kilima Labs", slug: "kilima-labs" }, 201);
    const transport = serverTransport({
        gatewayUrl: "http://gateway.test",
        tokens: anonymousAccessTokens,
        fetch: (request) => {
            requests.push(request);
            return Promise.resolve(reply(request));
        },
    });
    repository = httpSignupRepository(createApiClients(transport).identity);
});

describe("httpSignupRepository.signUp", () => {
    it("posts the details with the idempotency key and maps the receipt", async () => {
        const receipt = await repository.signUp(details, "key-1");

        const request = requests[0];
        expect(request?.method).toBe("POST");
        expect(request?.url).toBe("http://gateway.test/api/v1/identity/signup");
        expect(request?.headers.get("Idempotency-Key")).toBe("key-1");
        expect(await request?.json()).toEqual(details);
        expect(receipt).toEqual({ orgId: "org-1", organizationName: "Kilima Labs", slug: "kilima-labs" });
    });

    it("raises the backend's error envelope as an ApiError", async () => {
        reply = () =>
            json(409, {
                success: false,
                message: "An account with this email already exists",
                error: "CONFLICT",
                statusCode: 409,
            });

        await expect(repository.signUp(details, "key-1")).rejects.toMatchObject({
            constructor: ApiError,
            status: 409,
            code: "CONFLICT",
        });
    });

    it("refuses a receipt that breaks the contract", async () => {
        reply = () => ok({ orgName: "Kilima Labs" }, 201);

        await expect(repository.signUp(details, "key-1")).rejects.toBeInstanceOf(UnexpectedResponseError);
    });
});

describe("httpSignupRepository.checkSlug", () => {
    it("asks for the slug's availability", async () => {
        reply = () => ok({ slug: "kilima", available: false });

        expect(await repository.checkSlug("kilima")).toEqual({ slug: "kilima", available: false });
        expect(requests[0]?.url).toBe("http://gateway.test/api/v1/identity/signup/slugs/kilima/availability");
    });

    it("carries the cancellation signal on the request", async () => {
        reply = () => ok({ slug: "kilima", available: true });
        const controller = new AbortController();

        await repository.checkSlug("kilima", { signal: controller.signal });
        controller.abort();

        expect(requests[0]?.signal.aborted).toBe(true);
    });
});
