"use client";

import { Button, Icon } from "@/shared/presentation/ui";
import Link from "next/link";
import { unreadBadge } from "../domain/notification";
import styles from "./NotificationBell.module.css";
import { notificationPaths } from "./notification-paths";
import { useUnreadCount } from "./use-unread-count";

export function NotificationBell() {
    const count = useUnreadCount({ poll: true });
    const unread = count !== undefined && count > 0;

    return (
        <Button asChild variant="ghost" iconOnly className={styles.bell}>
            <Link
                href={notificationPaths.list}
                aria-label={unread ? `Notifications, ${String(count)} unread` : "Notifications"}
            >
                <Icon name="bell" />
                {unread && (
                    <span className={styles.count} aria-hidden="true">
                        {unreadBadge(count)}
                    </span>
                )}
            </Link>
        </Button>
    );
}
