import type { Page } from "@/shared/domain/page";
import { error } from "@/test/msw/envelopes";
import { notificationDto } from "@/test/msw/notification";
import { renderWithProviders, type RenderWithProvidersOptions } from "@/test/render";
import type { InfiniteData } from "@tanstack/react-query";
import { screen, waitFor, within } from "@testing-library/react";
import type { OnUrlUpdateFunction } from "nuqs/adapters/testing";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Notification } from "../domain/notification";
import { NotificationBell } from "./NotificationBell";
import { NotificationsView } from "./NotificationsView";
import { notificationKeys } from "./queries";
import { NotificationsBackend } from "./testing/notifications-backend";

const BRIAN = notificationDto();
const INVITE_EXPIRING = notificationDto({
    deliveryId: "7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f",
    title: "Your invite to david.kariuki@kilimalabs.co expires tomorrow",
    body: "Grace Njeri invited David to Kilima Labs 2 days ago.",
    sentAt: "2026-01-15T09:00:00Z",
});
const DISCONNECTED = notificationDto({
    deliveryId: "2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e",
    title: "Receipts worker was disconnected from GitHub",
    body: "Pallet can no longer read kilima-labs/receipts.",
    sentAt: "2026-01-12T09:12:00Z",
});
const WELCOME = notificationDto({
    deliveryId: "3c4d5e6f-7a8b-4c9d-8e0f-2a3b4c5d6e7f",
    title: "Welcome to Pallet, Amani",
    body: "Your account is ready.",
    read: true,
    sentAt: "2025-12-02T10:05:00Z",
    readAt: "2025-12-02T10:30:00Z",
});

function deferred() {
    let release: (response: Response) => void = () => undefined;
    const response = new Promise<Response>((resolve) => {
        release = resolve;
    });
    return { response, release };
}

interface Scenario extends Pick<RenderWithProvidersOptions, "searchParams" | "onUrlUpdate"> {
    readonly backend?: NotificationsBackend;
}

/** The page and the bell, as the shell mounts them, against the fake backend. */
async function renderNotifications({
    backend = new NotificationsBackend([BRIAN, INVITE_EXPIRING, DISCONNECTED, WELCOME]),
    ...options
}: Scenario = {}) {
    backend.install();
    const rendered = renderWithProviders(
        <>
            <NotificationBell />
            <NotificationsView />
        </>,
        { urlMemory: true, ...options },
    );
    await waitFor(() => {
        expect(screen.queryByRole("status", { name: "Loading notifications" })).not.toBeInTheDocument();
    });
    return { ...rendered, backend };
}

const item = (title: string) => screen.getByRole("article", { name: title });
const titles = () =>
    screen.queryAllByRole("article").map((article) => within(article).getByRole("heading").textContent);
const isUnread = (title: string) => within(item(title)).queryByText("Unread") !== null;
const bell = () => screen.getByRole("link", { name: /^Notifications/ });

beforeEach(() => {
    vi.stubEnv("TZ", "Africa/Nairobi");
});

describe("NotificationsView", () => {
    it("lists every notification newest first under its day, marking the unread ones", async () => {
        await renderNotifications();

        expect(titles()).toEqual([BRIAN.title, INVITE_EXPIRING.title, DISCONNECTED.title, WELCOME.title]);
        expect(screen.getAllByRole("heading", { level: 2 }).map((heading) => heading.textContent)).toEqual([
            "Today",
            "Earlier this week",
            "December 2025",
        ]);
        expect(within(item(BRIAN.title)).getByText(BRIAN.body)).toBeInTheDocument();
        expect(within(item(BRIAN.title)).getByText("18 minutes ago")).toBeInTheDocument();
        expect(isUnread(BRIAN.title)).toBe(true);
        expect(isUnread(WELCOME.title)).toBe(false);
        expect(within(item(WELCOME.title)).queryByRole("button")).not.toBeInTheDocument();
        expect(screen.getByText("3 unread")).toBeInTheDocument();
        expect(screen.getByRole("radio", { name: "Unread (3)" })).toBeInTheDocument();
        expect(screen.getByText("Showing 4 of 4")).toBeInTheDocument();
    });

    it("keeps the filter in the URL and asks for unread notifications only", async () => {
        const updates: string[] = [];
        const onUrlUpdate: OnUrlUpdateFunction = ({ queryString }) => {
            updates.push(queryString);
        };
        const { user, backend } = await renderNotifications({ onUrlUpdate });

        await user.click(screen.getByRole("radio", { name: "Unread (3)" }));

        await waitFor(() => {
            expect(titles()).toEqual([BRIAN.title, INVITE_EXPIRING.title, DISCONNECTED.title]);
        });
        expect(updates.at(-1)).toBe("?status=UNREAD");
        expect(backend.listed("status")).toEqual(["ALL", "UNREAD"]);

        await user.click(screen.getByRole("radio", { name: "All" }));

        await waitFor(() => {
            expect(titles()).toHaveLength(4);
        });
        expect(updates.at(-1)).toBe("");
    });

    it("reads the filter from the URL", async () => {
        const { backend } = await renderNotifications({ searchParams: "?status=UNREAD" });

        expect(backend.listed("status")).toEqual(["UNREAD"]);
        expect(screen.getByRole("radio", { name: "Unread (3)" })).toBeChecked();
    });

    it("says everything is caught up when nothing is unread", async () => {
        await renderNotifications({ backend: new NotificationsBackend([WELCOME]), searchParams: "?status=UNREAD" });

        expect(screen.getByRole("heading", { name: "You're all caught up" })).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Mark all as read" })).not.toBeInTheDocument();
    });

    it("says there is nothing yet when there are no notifications at all", async () => {
        await renderNotifications({ backend: new NotificationsBackend([]) });

        expect(screen.getByRole("heading", { name: "No notifications yet" })).toBeInTheDocument();
    });

    it("loads the next twenty on request and appends them", async () => {
        const many = Array.from({ length: 25 }, (_, index) =>
            notificationDto({
                deliveryId: crypto.randomUUID(),
                title: `Notification ${String(index + 1)}`,
                sentAt: new Date(Date.parse("2026-01-15T11:00:00Z") - index * 60_000).toISOString(),
            }),
        );
        const { user, backend } = await renderNotifications({ backend: new NotificationsBackend(many) });

        expect(titles()).toHaveLength(20);
        expect(screen.getByText("Showing 20 of 25")).toBeInTheDocument();

        await user.click(screen.getByRole("button", { name: "Load more" }));

        await waitFor(() => {
            expect(titles()).toHaveLength(25);
        });
        expect(titles().at(-1)).toBe("Notification 25");
        expect(backend.listed("page")).toEqual(["0", "1"]);
        expect(screen.getByText("Showing 25 of 25")).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();
    });

    it("marks one read at once: the item, the other filter's list and the bell, with no list read again", async () => {
        const { user, backend, queryClient } = await renderNotifications({ searchParams: "?status=UNREAD" });
        await user.click(screen.getByRole("radio", { name: "All" }));
        await waitFor(() => {
            expect(titles()).toHaveLength(4);
        });
        await screen.findByRole("link", { name: "Notifications, 3 unread" });
        const requests = backend.listRequests.length;

        await user.click(screen.getByRole("button", { name: `Mark as read: ${BRIAN.title}` }));

        expect(isUnread(BRIAN.title)).toBe(false);
        expect(within(item(BRIAN.title)).queryByRole("button")).not.toBeInTheDocument();
        expect(item(BRIAN.title)).toHaveFocus();
        expect(bell()).toHaveAccessibleName("Notifications, 2 unread");
        expect(screen.getByText("2 unread")).toBeInTheDocument();
        const unreadList = queryClient.getQueryData<InfiniteData<Page<Notification>>>(notificationKeys.list("UNREAD"));
        expect(unreadList?.pages[0]?.items.find((notification) => notification.id === BRIAN.deliveryId)?.read).toBe(
            true,
        );

        expect(await screen.findByText("Marked as read")).toBeInTheDocument();
        expect(backend.markedRead).toEqual([BRIAN.deliveryId]);
        expect(backend.listRequests).toHaveLength(requests);
        expect(backend.unreadCountRequests).toBe(1);
    });

    it("puts one back as it was when marking it read fails", async () => {
        const { user, backend } = await renderNotifications();
        await screen.findByRole("link", { name: "Notifications, 3 unread" });
        const failure = deferred();
        backend.markReadReply = () => failure.response;

        await user.click(screen.getByRole("button", { name: `Mark as read: ${BRIAN.title}` }));

        expect(isUnread(BRIAN.title)).toBe(false);
        expect(bell()).toHaveAccessibleName("Notifications, 2 unread");

        failure.release(error(500, "INTERNAL_ERROR"));

        await waitFor(() => {
            expect(isUnread(BRIAN.title)).toBe(true);
        });
        expect(bell()).toHaveAccessibleName("Notifications, 3 unread");
        expect(await screen.findByText(/Something went wrong on our side/)).toBeInTheDocument();
    });

    it("drops one that no longer exists for the caller", async () => {
        const { user, backend } = await renderNotifications();
        backend.markReadReply = () => error(404, "NOT_FOUND", { message: "Notification not found" });

        await user.click(screen.getByRole("button", { name: `Mark as read: ${BRIAN.title}` }));

        await waitFor(() => {
            expect(screen.queryByRole("article", { name: BRIAN.title })).not.toBeInTheDocument();
        });
        expect(screen.getByText("Showing 3 of 3")).toBeInTheDocument();
        expect(screen.queryByText(/Something went wrong/)).not.toBeInTheDocument();
    });

    it("marks everything read and sets the bell to zero, then reads the list again", async () => {
        const { user, backend } = await renderNotifications();
        await screen.findByRole("link", { name: "Notifications, 3 unread" });
        const requests = backend.listRequests.length;

        await user.click(screen.getByRole("button", { name: "Mark all as read" }));

        expect(bell()).toHaveAccessibleName("Notifications");
        expect(titles().filter(isUnread)).toEqual([]);
        expect(screen.queryByText("3 unread")).not.toBeInTheDocument();
        expect(await screen.findByText("All notifications marked as read")).toBeInTheDocument();
        expect(backend.markAllReadCalls).toBe(1);
        await waitFor(() => {
            expect(backend.listRequests.length).toBe(requests + 1);
        });
        expect(bell()).toHaveAccessibleName("Notifications");
    });

    it("restores every notification and the bell when marking everything read fails", async () => {
        const { user, backend } = await renderNotifications();
        await screen.findByRole("link", { name: "Notifications, 3 unread" });
        backend.markAllReadReply = () => error(500, "INTERNAL_ERROR");

        await user.click(screen.getByRole("button", { name: "Mark all as read" }));

        expect(await screen.findByText(/Something went wrong on our side/)).toBeInTheDocument();
        expect(titles().filter(isUnread)).toEqual([BRIAN.title, INVITE_EXPIRING.title, DISCONNECTED.title]);
        expect(bell()).toHaveAccessibleName("Notifications, 3 unread");
    });

    it("renders markup in a notification as text", async () => {
        const html = notificationDto({
            title: "<b>Kilima</b> Labs",
            body: '<b>bold</b><img src=x onerror="alert(1)">',
        });
        await renderNotifications({ backend: new NotificationsBackend([html]) });

        const article = screen.getByRole("article", { name: "<b>Kilima</b> Labs" });
        expect(within(article).getByText('<b>bold</b><img src=x onerror="alert(1)">')).toBeInTheDocument();
        expect(article.querySelector("b, img")).toBeNull();
    });

    it("offers a retry when the list can't be read", async () => {
        const backend = new NotificationsBackend([BRIAN]);
        backend.listFailure = () => error(503, "SERVICE_UNAVAILABLE");
        backend.install();
        const { user } = renderWithProviders(<NotificationsView />);
        const retry = await screen.findByRole("button", { name: /Try again/ });

        backend.listFailure = undefined;
        await user.click(retry);

        expect(await screen.findByRole("article", { name: BRIAN.title })).toBeInTheDocument();
    });
});
