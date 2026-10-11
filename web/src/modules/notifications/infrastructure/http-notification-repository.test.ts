import { anonymousAccessTokens } from "@/shared/application/access-token";
import { ApiError, UnexpectedResponseError } from "@/shared/domain/errors";
import { asDeliveryId } from "@/shared/domain/ids";
import { createApiClients } from "@/shared/infrastructure/api/clients";
import { serverTransport } from "@/shared/infrastructure/api/server-transport";
import { beforeEach, describe, expect, it } from "vitest";
import type { NotificationRepository } from "../application/ports";
import { httpNotificationRepository } from "./http-notification-repository";

const DELIVERY_ID = "5d6b1a52-3f0e-4c1e-9a7b-0f1e2d3c4b5a";

const json = (body: unknown, status = 200) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const ok = (data: unknown) => json({ success: true, message: "OK", data });

const wireNotification = {
    deliveryId: DELIVERY_ID,
    title: "Brian Mwangi joined Kilima Labs",
    body: "Brian accepted your invite and joined as a developer.",
    read: false,
    sentAt: "2026-01-15T11:00:00Z",
    readAt: null,
};

let requests: Request[];
let reply: () => Response;
let repository: NotificationRepository;

beforeEach(() => {
    requests = [];
    reply = () => ok(null);
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

describe("httpNotificationRepository.list", () => {
    it("asks for one page of a filter and maps each delivery", async () => {
        reply = () =>
            ok({
                content: [wireNotification],
                page: 1,
                size: 20,
                totalElements: 21,
                totalPages: 2,
                first: false,
                last: true,
            });

        const page = await repository.list("UNREAD", { page: 1, size: 20 });

        expect(requests[0]?.url).toBe(
            "http://gateway.test/api/v1/notification/notifications?page=1&size=20&status=UNREAD",
        );
        expect(page).toEqual({
            items: [
                {
                    id: DELIVERY_ID,
                    title: wireNotification.title,
                    body: wireNotification.body,
                    read: false,
                    sentAt: "2026-01-15T11:00:00Z",
                    readAt: null,
                },
            ],
            page: 1,
            size: 20,
            totalItems: 21,
            totalPages: 2,
            isFirst: false,
            isLast: true,
        });
    });

    it("accepts a delivery that has not been stamped as sent yet", async () => {
        reply = () =>
            ok({
                content: [{ ...wireNotification, sentAt: null }],
                page: 0,
                size: 20,
                totalElements: 1,
                totalPages: 1,
                first: true,
                last: true,
            });

        expect((await repository.list("ALL", { page: 0, size: 20 })).items[0]?.sentAt).toBeNull();
    });
});

describe("httpNotificationRepository.markRead", () => {
    it("marks the delivery read", async () => {
        await repository.markRead(asDeliveryId(DELIVERY_ID));

        expect(requests[0]?.method).toBe("PATCH");
        expect(requests[0]?.url).toBe(`http://gateway.test/api/v1/notification/notifications/${DELIVERY_ID}/read`);
    });

    it("surfaces a missing delivery as an API error", async () => {
        reply = () =>
            json({ success: false, message: "Notification not found", error: "NOT_FOUND", statusCode: 404 }, 404);

        await expect(repository.markRead(asDeliveryId(DELIVERY_ID))).rejects.toBeInstanceOf(ApiError);
    });
});

describe("httpNotificationRepository.markAllRead", () => {
    it("marks every notification read", async () => {
        await repository.markAllRead();

        expect(requests[0]?.method).toBe("POST");
        expect(requests[0]?.url).toBe("http://gateway.test/api/v1/notification/notifications/read-all");
    });
});

describe("httpNotificationRepository.unreadCount", () => {
    it("reads the count", async () => {
        reply = () => ok(3);

        expect(await repository.unreadCount()).toBe(3);
        expect(requests[0]?.url).toBe("http://gateway.test/api/v1/notification/notifications/unread-count");
    });

    it("refuses a count that is not a whole, non-negative number", async () => {
        reply = () => ok(-1);

        await expect(repository.unreadCount()).rejects.toBeInstanceOf(UnexpectedResponseError);
    });
});
