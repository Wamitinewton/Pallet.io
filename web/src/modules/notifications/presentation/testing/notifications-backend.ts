import { ok, page } from "@/test/msw/envelopes";
import type { NotificationDto } from "@/test/msw/notification";
import { bffUrl, server } from "@/test/msw/server";
import { http } from "msw";

export const NOTIFICATIONS_URL = bffUrl("/notification/notifications");

type Reply = () => Response | Promise<Response>;

/** The notification service as far as the page and the bell use it, newest first, for one caller. */
export class NotificationsBackend {
    readonly listRequests: URLSearchParams[] = [];
    readonly markedRead: string[] = [];
    markAllReadCalls = 0;
    unreadCountRequests = 0;
    /** Answers every list request with this while set. */
    listFailure: Reply | undefined;
    /** Answers marking one read with this instead of marking it, while set. */
    markReadReply: Reply | undefined;
    /** Answers marking all read with this instead of marking them, while set. */
    markAllReadReply: Reply | undefined;

    constructor(public notifications: NotificationDto[] = []) {}

    get unread(): number {
        return this.notifications.filter((notification) => !notification.read).length;
    }

    listed(param: string): (string | null)[] {
        return this.listRequests.map((request) => request.get(param));
    }

    install(): void {
        server.use(
            http.get(`${NOTIFICATIONS_URL}/unread-count`, () => {
                this.unreadCountRequests += 1;
                return ok(this.unread);
            }),
            http.get(NOTIFICATIONS_URL, ({ request }) => {
                const params = new URL(request.url).searchParams;
                this.listRequests.push(params);
                if (this.listFailure !== undefined) return this.listFailure();
                const matching = this.notifications.filter(
                    (notification) => params.get("status") !== "UNREAD" || !notification.read,
                );
                const number = Number(params.get("page") ?? "0");
                const size = Number(params.get("size") ?? "20");
                return page(matching.slice(number * size, (number + 1) * size), {
                    page: number,
                    size,
                    totalElements: matching.length,
                });
            }),
            http.patch(`${NOTIFICATIONS_URL}/:deliveryId/read`, ({ params }) => {
                const id = String(params.deliveryId);
                this.markedRead.push(id);
                if (this.markReadReply !== undefined) return this.markReadReply();
                this.notifications = this.notifications.map((notification) =>
                    notification.deliveryId === id ? { ...notification, read: true } : notification,
                );
                return ok(null, "Notification marked as read");
            }),
            http.post(`${NOTIFICATIONS_URL}/read-all`, () => {
                this.markAllReadCalls += 1;
                if (this.markAllReadReply !== undefined) return this.markAllReadReply();
                this.notifications = this.notifications.map((notification) => ({ ...notification, read: true }));
                return ok(null, "All notifications marked as read");
            }),
        );
    }
}
