import { anonymousAccessTokens } from "@/shared/application/access-token";
import { ApiError } from "@/shared/domain/errors";
import { createApiClients } from "@/shared/infrastructure/api/clients";
import { serverTransport } from "@/shared/infrastructure/api/server-transport";
import { beforeEach, describe, expect, it } from "vitest";
import type { PasswordRepository } from "../application/ports";
import { httpPasswordRepository } from "./http-password-repository";

const TOKEN = "q3Jx0f2Zr9VbL1mN8sT4uW6yA7cE5gH0iK2oP_-dQ1s";

const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

let requests: Request[];
let reply: () => Response;
let repository: PasswordRepository;

beforeEach(() => {
    requests = [];
    reply = () => json(200, { success: true, message: "Password reset", data: null });
    const transport = serverTransport({
        gatewayUrl: "http://gateway.test",
        tokens: anonymousAccessTokens,
        fetch: (request) => {
            requests.push(request);
            return Promise.resolve(reply());
        },
    });
    repository = httpPasswordRepository(createApiClients(transport).identity);
});

describe("httpPasswordRepository", () => {
    it("accepts the 202 for a reset request", async () => {
        reply = () =>
            json(202, { success: true, message: "Password reset instructions sent if the account exists", data: null });

        await repository.requestReset("amani@kilimalabs.co");

        expect(requests[0]?.url).toBe("http://gateway.test/api/v1/identity/auth/password/forgot");
        expect(await requests[0]?.json()).toEqual({ email: "amani@kilimalabs.co" });
    });

    it("posts the token and the new password", async () => {
        await repository.reset({ token: TOKEN, newPassword: "mango-season-in-kisumu" });

        expect(requests[0]?.url).toBe("http://gateway.test/api/v1/identity/auth/password/reset");
        expect(await requests[0]?.json()).toEqual({ token: TOKEN, newPassword: "mango-season-in-kisumu" });
    });

    it("raises INVALID_TOKEN as an ApiError", async () => {
        reply = () =>
            json(400, {
                success: false,
                message: "This link is invalid or has expired.",
                error: "INVALID_TOKEN",
                statusCode: 400,
            });

        await expect(repository.reset({ token: TOKEN, newPassword: "mango-season-in-kisumu" })).rejects.toMatchObject({
            constructor: ApiError,
            code: "INVALID_TOKEN",
        });
    });
});
