import { asDeliveryId } from "@/shared/domain/ids";
import { asIsoInstant } from "@/shared/domain/instant";
import type { NotificationClient } from "@/shared/infrastructure/api/clients";
import { unwrap, unwrapPage } from "@/shared/infrastructure/api/envelope";
import { toPageQuery } from "@/shared/infrastructure/api/page-query";
import { z } from "zod";
import type { NotificationRepository } from "../application/ports";
import type { Notification } from "../domain/notification";

const unreadCountSchema = z.number().int().nonnegative();
const noData = z.null();

const notificationSchema = z.object({
    deliveryId: z.uuid(),
    title: z.string(),
    body: z.string(),
    read: z.boolean(),
    sentAt: z.iso.datetime({ offset: true }).nullable(),
    readAt: z.iso.datetime({ offset: true }).nullable(),
});

function toNotification(dto: z.output<typeof notificationSchema>): Notification {
    return {
        id: asDeliveryId(dto.deliveryId),
        title: dto.title,
        body: dto.body,
        read: dto.read,
        sentAt: dto.sentAt === null ? null : asIsoInstant(dto.sentAt),
        readAt: dto.readAt === null ? null : asIsoInstant(dto.readAt),
    };
}

export function httpNotificationRepository(notification: NotificationClient): NotificationRepository {
    return {
        async list(filter, page) {
            return unwrapPage(
                await notification.GET("/notifications", {
                    params: { query: { ...toPageQuery(page), status: filter } },
                }),
                notificationSchema,
                toNotification,
            );
        },

        async markRead(deliveryId) {
            unwrap(
                await notification.PATCH("/notifications/{deliveryId}/read", { params: { path: { deliveryId } } }),
                noData,
            );
        },

        async markAllRead() {
            unwrap(await notification.POST("/notifications/read-all"), noData);
        },

        async unreadCount() {
            return unwrap(await notification.GET("/notifications/unread-count"), unreadCountSchema);
        },
    };
}
