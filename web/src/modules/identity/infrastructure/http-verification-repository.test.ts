import { anonymousAccessTokens } from "@/shared/application/access-token";
import { ApiError } from "@/shared/domain/errors";
import { createApiClients } from "@/shared/infrastructure/api/clients";
import { serverTransport } from "@/shared/infrastructure/api/server-transport";
import { beforeEach, describe, expect, it } from "vitest";
import type { VerificationRepository } from "../application/ports";
import { httpVerificationRepository } from "./http-verification-repository";

const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

let requests: Request[];
let reply: () => Response;
let repository: VerificationRepository;

beforeEach(() => {
    requests = [];
    reply = () => json(200, { success: true, message: "Email verified", data: null });
    const transport = serverTransport({
        gatewayUrl: "http://gateway.test",
        tokens: anonymousAccessTokens,
        fetch: (request) => {
            requests.push(request);
            return Promise.resolve(reply());
        },
    });
    repository = httpVerificationRepository(createApiClients(transport).identity);
});

describe("httpVerificationRepository", () => {
    it("posts the email and code to verify", async () => {
        await repository.verify({ email: "amani@kilimalabs.co", code: "K7QM3WZP" });

        expect(requests[0]?.url).toBe("http://gateway.test/api/v1/identity/auth/email/verify");
        expect(await requests[0]?.json()).toEqual({ email: "amani@kilimalabs.co", code: "K7QM3WZP" });
    });

    it("raises INVALID_TOKEN as an ApiError", async () => {
        reply = () =>
            json(400, {
                success: false,
                message: "This link is invalid or has expired.",
                error: "INVALID_TOKEN",
                statusCode: 400,
            });

        await expect(repository.verify({ email: "amani@kilimalabs.co", code: "K7QM3WZP" })).rejects.toMatchObject({
            constructor: ApiError,
            code: "INVALID_TOKEN",
        });
    });

    it("accepts the 202 for a resend", async () => {
        reply = () => json(202, { success: true, message: "Verification code sent if the account exists", data: null });

        await repository.resend("amani@kilimalabs.co");

        expect(requests[0]?.url).toBe("http://gateway.test/api/v1/identity/auth/email/resend-verification");
        expect(await requests[0]?.json()).toEqual({ email: "amani@kilimalabs.co" });
    });
});
