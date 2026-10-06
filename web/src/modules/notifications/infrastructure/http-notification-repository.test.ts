import { anonymousAccessTokens } from "@/shared/application/access-token";
import { UnexpectedResponseError } from "@/shared/domain/errors";
import { createApiClients } from "@/shared/infrastructure/api/clients";
import { serverTransport } from "@/shared/infrastructure/api/server-transport";
import { beforeEach, describe, expect, it } from "vitest";
import type { NotificationRepository } from "../application/ports";
import { httpNotificationRepository } from "./http-notification-repository";

const ok = (data: unknown) =>
    new Response(JSON.stringify({ success: true, message: "OK", data }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
    });

let requests: Request[];
let reply: () => Response;
let repository: NotificationRepository;

beforeEach(() => {
    requests = [];
    reply = () => ok(3);
    const transport = serverTransport({
        gatewayUrl: "http://gateway.test",
        tokens: anonymousAccessTokens,
        fetch: (request) => {
            requests.push(request);
            return Promise.resolve(reply());
        },
    });
    repository = httpNotificationRepository(createApiClients(transport).notification);
});

describe("httpNotificationRepository.unreadCount", () => {
    it("reads the count", async () => {
        expect(await repository.unreadCount()).toBe(3);
        expect(requests[0]?.url).toBe("http://gateway.test/api/v1/notification/notifications/unread-count");
    });

    it("refuses a count that is not a whole, non-negative number", async () => {
        reply = () => ok(-1);

        await expect(repository.unreadCount()).rejects.toBeInstanceOf(UnexpectedResponseError);
    });
});
