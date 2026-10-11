"use client";

import { Button, RelativeTime, VisuallyHidden } from "@/shared/presentation/ui";
import { useId, useRef } from "react";
import type { Notification } from "../domain/notification";
import { MARK_READ_COPY, markReadLabel, UNREAD_DOT_COPY } from "./notification-copy";
import styles from "./Notifications.module.css";

export interface NotificationItemProps {
    readonly notification: Notification;
    readonly onMarkRead: (notification: Notification) => void;
}

/** Title and body render as text: both carry names people chose, such as organizations and members. */
export function NotificationItem({ notification, onMarkRead }: NotificationItemProps) {
    const titleId = useId();
    const article = useRef<HTMLElement>(null);

    return (
        <article
            ref={article}
            tabIndex={-1}
            aria-labelledby={titleId}
            className={styles.note}
            data-read={notification.read || undefined}
        >
            <span className={styles.dot}>
                {!notification.read && <VisuallyHidden>{UNREAD_DOT_COPY}</VisuallyHidden>}
            </span>
            <div className={styles.content}>
                <h3 id={titleId} className={styles.title}>
                    {notification.title}
                </h3>
                <p className={styles.body}>{notification.body}</p>
            </div>
            {notification.sentAt !== null && <RelativeTime dateTime={notification.sentAt} className={styles.time} />}
            {!notification.read && (
                <div className={styles.actions}>
                    <Button
                        variant="ghost"
                        size="sm"
                        aria-label={markReadLabel(notification.title)}
                        onClick={() => {
                            article.current?.focus();
                            onMarkRead(notification);
                        }}
                    >
                        {MARK_READ_COPY}
                    </Button>
                </div>
            )}
        </article>
    );
}
