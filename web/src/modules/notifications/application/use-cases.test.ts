import { ApiError, NetworkError } from "@/shared/domain/errors";
import { asDeliveryId } from "@/shared/domain/ids";
import type { PageRequest } from "@/shared/domain/page";
import { describe, expect, it } from "vitest";
import type { ReadFilter } from "../domain/notification";
import type { NotificationRepository } from "./ports";
import { makeNotificationUseCases } from "./use-cases";

const ID = asDeliveryId("5d6b1a52-3f0e-4c1e-9a7b-0f1e2d3c4b5a");
const refusal = (status: number, code: string) => new ApiError(status, code, code, [], {}, undefined);

function repository(overrides: Partial<NotificationRepository> = {}) {
    const listed: { filter: ReadFilter; page: PageRequest }[] = [];
    const notifications: NotificationRepository = {
        list: (filter, page) => {
            listed.push({ filter, page });
            return Promise.resolve({
                items: [],
                page: page.page,
                size: page.size,
                totalItems: 0,
                totalPages: 0,
                isFirst: true,
                isLast: true,
            });
        },
        markRead: () => Promise.resolve(),
        markAllRead: () => Promise.resolve(),
        unreadCount: () => Promise.resolve(3),
        ...overrides,
    };
    return { useCases: makeNotificationUseCases({ notifications }), listed };
}

describe("notification use cases", () => {
    it("reads the caller's unread count", async () => {
        expect(await repository().useCases.getUnreadCount()).toBe(3);
    });

    it("lists twenty a page", async () => {
        const { useCases, listed } = repository();

        await useCases.listNotifications("UNREAD", 2);

        expect(listed).toEqual([{ filter: "UNREAD", page: { page: 2, size: 20 } }]);
    });

    it("marks one read", async () => {
        expect(await repository().useCases.markRead(ID)).toBe("read");
    });

    it("answers gone for a notification that no longer exists for the caller", async () => {
        const { useCases } = repository({ markRead: () => Promise.reject(refusal(404, "NOT_FOUND")) });

        expect(await useCases.markRead(ID)).toBe("gone");
    });

    it.each([refusal(500, "INTERNAL_ERROR"), new NetworkError()])(
        "lets any other failure through: %s",
        async (error) => {
            const { useCases } = repository({ markRead: () => Promise.reject(error) });

            await expect(useCases.markRead(ID)).rejects.toBe(error);
        },
    );
});
