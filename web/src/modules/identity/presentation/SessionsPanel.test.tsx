import { error, ok } from "@/test/msw/envelopes";
import { sessionDto, sessionSummaryBody, type SessionDto } from "@/test/msw/identity";
import { bffUrl, server, sessionApiUrl } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";
import { screen, waitFor, within } from "@testing-library/react";
import { http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SessionsPanel } from "./SessionsPanel";

const router = vi.hoisted(() => ({ replace: vi.fn(), refresh: vi.fn(), push: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router }));

const SESSIONS_URL = bffUrl("/identity/users/me/sessions");

const thisDevice = sessionDto();
const laptop = sessionDto({
    id: "kc-laptop",
    ipAddress: "41.90.64.211",
    startedAt: "2026-01-12T19:02:00Z",
    lastAccessedAt: "2026-01-15T09:00:00Z",
});
const phone = sessionDto({
    id: "kc-phone",
    ipAddress: "102.68.77.5",
    startedAt: "2026-01-06T10:15:00Z",
    lastAccessedAt: "2026-01-09T10:15:00Z",
});

let stored: SessionDto[];

function deferred() {
    let release: () => void = () => undefined;
    const gate = new Promise<void>((resolve) => {
        release = resolve;
    });
    return { gate, release };
}

function replyToEnd(reply: (id: string) => Response | Promise<Response>) {
    const ended: string[] = [];
    server.use(
        http.delete(`${SESSIONS_URL}/:sessionId`, ({ params }) => {
            const id = String(params.sessionId);
            ended.push(id);
            return reply(id);
        }),
    );
    return ended;
}

const rows = () => within(screen.getByRole("list", { name: "Where you're signed in" })).getAllByRole("listitem");
const endButtonName = (ip: string) => `Sign out the session from ${ip}`;
const endButton = (ip: string) => screen.getByRole("button", { name: endButtonName(ip) });
const queryEndButton = (ip: string) => screen.queryByRole("button", { name: endButtonName(ip) });

async function renderPanel() {
    const rendered = renderWithProviders(<SessionsPanel />);
    await screen.findByText("This device");
    return rendered;
}

beforeEach(() => {
    vi.clearAllMocks();
    stored = [phone, thisDevice, laptop];
    server.use(
        http.get(SESSIONS_URL, () => ok(stored, "Sessions retrieved")),
        http.get(sessionApiUrl(""), () => ok(sessionSummaryBody(), "Current session")),
    );
});

describe("SessionsPanel", () => {
    it("lists the most recently active first and marks this device without offering to end it", async () => {
        await renderPanel();

        const [first, second, third] = rows();
        expect(first).toHaveTextContent("This device");
        expect(first).toHaveTextContent("197.237.14.82");
        expect(first).toHaveTextContent("Current");
        expect(screen.getAllByRole("button", { name: /^Sign out the session from/ })).toHaveLength(2);
        expect(second).toHaveTextContent("41.90.64.211");
        expect(third).toHaveTextContent("102.68.77.5");
        expect(queryEndButton("197.237.14.82")).not.toBeInTheDocument();
        expect(endButton("41.90.64.211")).toBeInTheDocument();
    });

    it("never shows a session id", async () => {
        await renderPanel();

        expect(document.body).not.toHaveTextContent("kc-this-device");
        expect(document.body).not.toHaveTextContent("kc-laptop");
    });

    it("asks before ending a session, then removes its row before the backend answers", async () => {
        const { gate, release } = deferred();
        const ended = replyToEnd(async (id) => {
            await gate;
            stored = stored.filter((session) => session.id !== id);
            return ok(null, "Session revoked");
        });
        const { user } = await renderPanel();

        await user.click(endButton("41.90.64.211"));
        const dialog = await screen.findByRole("dialog", { name: "Sign out this session?" });
        expect(ended).toEqual([]);
        await user.click(within(dialog).getByRole("button", { name: "Sign out session" }));

        await waitFor(() => {
            expect(queryEndButton("41.90.64.211")).not.toBeInTheDocument();
        });
        expect(rows()).toHaveLength(2);

        release();

        expect(await screen.findByText("Session signed out")).toBeInTheDocument();
        expect(ended).toEqual(["kc-laptop"]);
    });

    it("cancelling leaves the session alone", async () => {
        const ended = replyToEnd(() => ok(null));
        const { user } = await renderPanel();

        await user.click(endButton("41.90.64.211"));
        await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Cancel" }));

        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        expect(rows()).toHaveLength(3);
        expect(ended).toEqual([]);
    });

    it("removes a session that had already ended without reporting an error", async () => {
        replyToEnd((id) => {
            stored = stored.filter((session) => session.id !== id);
            return error(404, "SESSION_NOT_FOUND", { message: "Session not found." });
        });
        const { user } = await renderPanel();

        await user.click(endButton("102.68.77.5"));
        await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Sign out session" }));

        await waitFor(() => {
            expect(queryEndButton("102.68.77.5")).not.toBeInTheDocument();
        });
        expect(await screen.findByText("Session signed out")).toBeInTheDocument();
        expect(screen.queryByText(/still signed in/)).not.toBeInTheDocument();
    });

    it("brings the row back and says so when ending it fails", async () => {
        replyToEnd(() => error(503, "SERVICE_UNAVAILABLE"));
        const { user } = await renderPanel();

        await user.click(endButton("41.90.64.211"));
        await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Sign out session" }));

        expect(await screen.findByText(/That session is still signed in\./)).toBeInTheDocument();
        await waitFor(() => {
            expect(endButton("41.90.64.211")).toBeInTheDocument();
        });
    });

    it("signs out everywhere else after confirming, then reads the list again", async () => {
        let endedOthers = 0;
        server.use(
            http.delete(SESSIONS_URL, () => {
                endedOthers++;
                stored = [thisDevice];
                return ok(null, "Other sessions revoked");
            }),
        );
        const { user } = await renderPanel();

        await user.click(screen.getByRole("button", { name: "Sign out everywhere else" }));
        const dialog = await screen.findByRole("dialog", { name: "Sign out everywhere else?" });
        expect(dialog).toHaveTextContent("2 other sessions end right away.");
        await user.click(within(dialog).getByRole("button", { name: "Sign out 2 sessions" }));

        expect(await screen.findByText("Signed out of 2 other sessions")).toBeInTheDocument();
        await waitFor(() => {
            expect(rows()).toHaveLength(2);
        });
        expect(endedOthers).toBe(1);
        expect(screen.getByText("You aren't signed in anywhere else.")).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Sign out everywhere else" })).not.toBeInTheDocument();
    });

    it("draws an error with a retry when the list can't be read", async () => {
        let attempts = 0;
        server.use(
            http.get(SESSIONS_URL, () => {
                attempts++;
                return attempts === 1 ? error(503, "SERVICE_UNAVAILABLE") : ok(stored);
            }),
        );
        const { user } = renderWithProviders(<SessionsPanel />);

        await user.click(await screen.findByRole("button", { name: "Try again" }));

        expect(await screen.findByText("This device")).toBeInTheDocument();
    });
});
