import { ApiError } from "@/shared/domain/errors";
import type { DeliveryId } from "@/shared/domain/ids";
import type { NotificationRepository } from "./ports";

/** `gone`: the delivery no longer exists for the caller, which is not a failure to report. */
export type MarkReadOutcome = "read" | "gone";

export type MarkRead = (id: DeliveryId) => Promise<MarkReadOutcome>;

export function makeMarkRead(notifications: NotificationRepository): MarkRead {
    return async (id) => {
        try {
            await notifications.markRead(id);
            return "read";
        } catch (error) {
            if (error instanceof ApiError && error.status === 404) return "gone";
            throw error;
        }
    };
}
