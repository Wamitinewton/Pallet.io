import type { NotificationClient } from "@/shared/infrastructure/api/clients";
import { unwrap } from "@/shared/infrastructure/api/envelope";
import { z } from "zod";
import type { NotificationRepository } from "../application/ports";

const unreadCountSchema = z.number().int().nonnegative();

export function httpNotificationRepository(notification: NotificationClient): NotificationRepository {
    return {
        async unreadCount() {
            return unwrap(await notification.GET("/notifications/unread-count"), unreadCountSchema);
        },
    };
}
