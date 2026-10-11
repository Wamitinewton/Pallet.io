import type { components } from "@/shared/infrastructure/api/generated/notification";

type Schemas = components["schemas"];
export type NotificationDto = Required<Omit<Schemas["NotificationDto"], "sentAt" | "readAt">> & {
    sentAt: string | null;
    readAt: string | null;
};

export function notificationDto(overrides: Partial<NotificationDto> = {}): NotificationDto {
    return {
        deliveryId: "5d6b1a52-3f0e-4c1e-9a7b-0f1e2d3c4b5a",
        title: "Brian Mwangi joined Kilima Labs",
        body: "Brian accepted your invite and joined as a developer.",
        read: false,
        sentAt: "2026-01-15T11:42:00Z",
        readAt: null,
        ...overrides,
    };
}
