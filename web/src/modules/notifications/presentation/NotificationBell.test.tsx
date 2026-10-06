import { ok } from "@/test/msw/envelopes";
import { bffUrl, server } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";
import { act, screen } from "@testing-library/react";
import { http } from "msw";
import { describe, expect, it, vi } from "vitest";
import { NotificationBell } from "./NotificationBell";
import { UNREAD_COUNT_REFRESH_MS } from "./queries";

function replyWithCounts(...counts: number[]) {
    let requests = 0;
    server.use(
        http.get(bffUrl("/notification/notifications/unread-count"), () => {
            requests += 1;
            return ok(counts[Math.min(requests, counts.length) - 1]);
        }),
    );
    return () => requests;
}

describe("NotificationBell", () => {
    it("links to the notifications, saying how many are unread", async () => {
        replyWithCounts(3);
        renderWithProviders(<NotificationBell />);

        const bell = await screen.findByRole("link", { name: "Notifications, 3 unread" });
        expect(bell).toHaveAttribute("href", "/notifications");
        expect(bell).toHaveTextContent("3");
    });

    it("caps the badge but keeps the real count in the label", async () => {
        replyWithCounts(140);
        renderWithProviders(<NotificationBell />);

        expect(await screen.findByRole("link", { name: "Notifications, 140 unread" })).toHaveTextContent("99+");
    });

    it("shows no badge when everything is read", async () => {
        const requests = replyWithCounts(0);
        renderWithProviders(<NotificationBell />);

        await vi.waitFor(() => {
            expect(requests()).toBe(1);
        });
        expect(screen.getByRole("link", { name: "Notifications" })).toHaveTextContent("");
    });

    it("asks again once a minute, never sooner", async () => {
        vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
        const requests = replyWithCounts(3, 5);
        renderWithProviders(<NotificationBell />);
        await screen.findByRole("link", { name: "Notifications, 3 unread" });

        act(() => {
            vi.advanceTimersByTime(UNREAD_COUNT_REFRESH_MS - 1);
        });
        expect(requests()).toBe(1);

        act(() => {
            vi.advanceTimersByTime(1);
        });
        expect(await screen.findByRole("link", { name: "Notifications, 5 unread" })).toBeInTheDocument();
        expect(requests()).toBe(2);
        expect(UNREAD_COUNT_REFRESH_MS).toBe(60_000);
    });
});
