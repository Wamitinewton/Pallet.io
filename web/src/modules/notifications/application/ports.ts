export interface NotificationRepository {
    unreadCount(): Promise<number>;
}
