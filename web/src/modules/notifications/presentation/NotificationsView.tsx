"use client";

import { ErrorState, messageFor } from "@/shared/presentation/errors";
import { useClock } from "@/shared/presentation/providers";
import { PageBreadcrumbs } from "@/shared/presentation/shell";
import {
    Button,
    EmptyState,
    Icon,
    PageHead,
    PageNarrow,
    Panel,
    PanelFoot,
    PanelHead,
    Segmented,
    SegmentedItem,
    Skeleton,
} from "@/shared/presentation/ui";
import { useInfiniteQuery } from "@tanstack/react-query";
import { useId, useRef } from "react";
import { READ_FILTERS, type ReadFilter } from "../domain/notification";
import { groupByDay } from "../domain/notification-days";
import { loadedNotifications } from "./notification-cache";
import {
    dayHeading,
    EMPTY_COPY,
    FILTER_GROUP_LABEL_COPY,
    FILTER_LABEL,
    LOAD_MORE_COPY,
    LOAD_MORE_ERROR_TITLE_COPY,
    LOADING_COPY,
    MARK_ALL_READ_COPY,
    PAGE_DESCRIPTION_COPY,
    PAGE_TITLE_COPY,
    showingCopy,
    unreadCountCopy,
} from "./notification-copy";
import { useNotificationUseCases } from "./notification-use-cases";
import { NotificationItem } from "./NotificationItem";
import styles from "./Notifications.module.css";
import { notificationQueries } from "./queries";
import { useLoadOnScroll } from "./use-load-on-scroll";
import { useMarkAllRead, useMarkRead } from "./use-notification-mutations";
import { useReadFilter } from "./use-read-filter";
import { useTimeZone } from "./use-time-zone";
import { useUnreadCount } from "./use-unread-count";

const LOADING_ROWS = 4;

export function NotificationsView() {
    const [filter, showFilter] = useReadFilter();
    const unreadCount = useUnreadCount() ?? 0;
    const markAllRead = useMarkAllRead();
    const panel = useRef<HTMLElement>(null);

    return (
        <PageNarrow>
            <PageBreadcrumbs items={[{ label: PAGE_TITLE_COPY }]} />
            <PageHead
                title={PAGE_TITLE_COPY}
                description={PAGE_DESCRIPTION_COPY}
                actions={
                    unreadCount > 0 && (
                        <Button
                            variant="secondary"
                            loading={markAllRead.isPending}
                            onClick={() => {
                                panel.current?.focus();
                                markAllRead.mutate();
                            }}
                        >
                            <Icon name="check" />
                            {MARK_ALL_READ_COPY}
                        </Button>
                    )
                }
            />

            <Panel ref={panel} tabIndex={-1} aria-label={PAGE_TITLE_COPY} className={styles.panel}>
                <PanelHead>
                    <Segmented aria-label={FILTER_GROUP_LABEL_COPY} value={filter} onValueChange={showFilter}>
                        {READ_FILTERS.map((option) => (
                            <SegmentedItem key={option} value={option}>
                                {FILTER_LABEL[option]}
                                {option === "UNREAD" && unreadCount > 0 && ` (${unreadCount.toLocaleString("en")})`}
                            </SegmentedItem>
                        ))}
                    </Segmented>
                    {unreadCount > 0 && <span className={styles.count}>{unreadCountCopy(unreadCount)}</span>}
                </PanelHead>
                <NotificationList key={filter} filter={filter} />
            </Panel>
        </PageNarrow>
    );
}

function NotificationList({ filter }: Readonly<{ filter: ReadFilter }>) {
    const { listNotifications } = useNotificationUseCases();
    const list = useInfiniteQuery(notificationQueries.list(listNotifications, filter));
    const markRead = useMarkRead();
    const clock = useClock();
    const timeZone = useTimeZone();
    const groupHeadingId = useId();

    const notifications = loadedNotifications(list.data);
    const total = list.data?.pages.at(-1)?.totalItems ?? notifications.length;
    const canLoadMore = list.hasNextPage && !list.isFetchingNextPage;
    const loadMore = () => {
        if (canLoadMore) void list.fetchNextPage();
    };
    const sentinel = useLoadOnScroll<HTMLDivElement>(loadMore, canLoadMore && !list.isFetchNextPageError);

    if (list.isError && list.data === undefined) {
        return (
            <ErrorState
                error={list.error}
                headingLevel={2}
                retrying={list.isFetching}
                onRetry={() => void list.refetch()}
            />
        );
    }
    if (list.data === undefined) return <LoadingRows />;
    if (notifications.length === 0) {
        return <EmptyState icon="bell" title={EMPTY_COPY[filter].title} description={EMPTY_COPY[filter].description} />;
    }

    return (
        <>
            {groupByDay(notifications, clock.now(), timeZone).map((group) => {
                const headingId = `${groupHeadingId}-${group.key}`;
                return (
                    <section key={group.key} aria-labelledby={headingId} className={styles.group}>
                        <h2 id={headingId} className={styles.day}>
                            {dayHeading(group.day)}
                        </h2>
                        {group.notifications.map((notification) => (
                            <NotificationItem
                                key={notification.id}
                                notification={notification}
                                onMarkRead={(target) => {
                                    markRead.mutate(target);
                                }}
                            />
                        ))}
                    </section>
                );
            })}
            {list.hasNextPage && <div ref={sentinel} className={styles.sentinel} aria-hidden="true" />}
            <PanelFoot>
                <span>{showingCopy(notifications.length, Math.max(total, notifications.length))}</span>
                {list.isFetchNextPageError && (
                    <span role="alert">
                        {LOAD_MORE_ERROR_TITLE_COPY} {messageFor(list.error, {}, { reference: false })}
                    </span>
                )}
                {list.hasNextPage && (
                    <Button variant="secondary" size="sm" loading={list.isFetchingNextPage} onClick={loadMore}>
                        {LOAD_MORE_COPY}
                    </Button>
                )}
            </PanelFoot>
        </>
    );
}

function LoadingRows() {
    return (
        <div aria-busy="true" aria-label={LOADING_COPY} role="status">
            {Array.from({ length: LOADING_ROWS }, (_, index) => (
                <div key={index} className={styles.note}>
                    <span />
                    <div className={styles.loading}>
                        <Skeleton width="45%" height={14} />
                        <Skeleton width="80%" height={12} />
                    </div>
                    <Skeleton width={48} height={12} />
                </div>
            ))}
        </div>
    );
}
