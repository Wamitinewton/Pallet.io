import { ApiError, NetworkError } from "@/shared/domain/errors";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { z } from "zod";
import { browserTransport } from "./browser-transport";
import { createApiClients, type ApiClients } from "./clients";
import { unwrap } from "./envelope";
import { serverTransport } from "./server-transport";
import type { Transport } from "./transport";

const ok = (data: unknown) =>
    new Response(JSON.stringify({ success: true, message: "OK", data }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
    });

interface Captured {
    readonly request: Request;
    readonly init: RequestInit | undefined;
}

let captured: Captured[];
let reply: () => Response | Promise<Response>;

beforeEach(() => {
    captured = [];
    reply = () => ok({ count: 3 });
    vi.stubGlobal("fetch", (request: Request, init?: RequestInit) => {
        captured.push({ request, init });
        return Promise.resolve(reply());
    });
});

afterEach(() => {
    vi.unstubAllGlobals();
});

const unreadCount = async (clients: ApiClients) =>
    unwrap(await clients.notification.GET("/notifications/unread-count"), z.object({ count: z.number() }));

const last = (): Captured => {
    const call = captured.at(-1);
    if (!call) throw new Error("no request was made");
    return call;
};

describe("the same client call over both transports", () => {
    const transports: Record<string, () => Transport> = {
        server: () =>
            serverTransport({
                gatewayUrl: "http://gateway.test/",
                tokens: { accessToken: () => Promise.resolve("t") },
            }),
        browser: () => browserTransport({ baseUrl: "http://localhost/bff" }),
    };

    it.each(Object.keys(transports))("returns the unwrapped data over the %s transport", async (name) => {
        const clients = createApiClients(transports[name]?.() ?? browserTransport());

        await expect(unreadCount(clients)).resolves.toEqual({ count: 3 });
    });

    it.each(Object.keys(transports))("throws an ApiError for an error envelope over the %s transport", async (name) => {
        reply = () =>
            new Response(JSON.stringify({ success: false, message: "Nope", error: "ACCESS_DENIED", statusCode: 403 }), {
                status: 403,
                headers: { "Content-Type": "application/json" },
            });
        const clients = createApiClients(transports[name]?.() ?? browserTransport());

        await expect(unreadCount(clients)).rejects.toMatchObject({ kind: "api", status: 403, code: "ACCESS_DENIED" });
    });

    it.each(Object.keys(transports))("throws a NetworkError when fetch rejects over the %s transport", async (name) => {
        reply = () => Promise.reject(new TypeError("fetch failed"));
        const clients = createApiClients(transports[name]?.() ?? browserTransport());

        await expect(unreadCount(clients)).rejects.toBeInstanceOf(NetworkError);
    });
});

describe("serverTransport", () => {
    it("calls the gateway with the bearer token, a correlation id and no caching", async () => {
        const clients = createApiClients(
            serverTransport({
                gatewayUrl: "http://gateway.test/",
                tokens: { accessToken: () => Promise.resolve("access-token") },
                correlationId: () => "corr-42",
            }),
        );

        await unreadCount(clients);

        const { request, init } = last();
        expect(request.url).toBe("http://gateway.test/api/v1/notification/notifications/unread-count");
        expect(request.headers.get("Authorization")).toBe("Bearer access-token");
        expect(request.headers.get("X-Correlation-Id")).toBe("corr-42");
        expect(init?.cache).toBe("no-store");
    });

    it("sends no Authorization header when there is no token", async () => {
        const clients = createApiClients(
            serverTransport({
                gatewayUrl: "http://gateway.test",
                tokens: { accessToken: () => Promise.resolve(undefined) },
            }),
        );

        await unreadCount(clients);

        expect(last().request.headers.has("Authorization")).toBe(false);
    });
});

describe("browserTransport", () => {
    it("calls the BFF on its own origin with the CSRF header and never a token", async () => {
        const clients = createApiClients(browserTransport({ baseUrl: "http://localhost/bff" }));

        await unreadCount(clients);

        const { request, init } = last();
        expect(request.url).toBe("http://localhost/bff/notification/notifications/unread-count");
        expect(request.headers.get("X-Pallet-Request")).toBe("1");
        expect(request.headers.has("Authorization")).toBe(false);
        expect(init?.credentials).toBe("same-origin");
    });

    it("serializes page queries as flat parameters", async () => {
        reply = () => ok({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0, first: true, last: true });
        const clients = createApiClients(browserTransport({ baseUrl: "http://localhost/bff" }));

        await clients.notification.GET("/notifications", {
            params: { query: { page: 1, size: 20, sort: "createdAt,desc" } },
        });

        const url = new URL(last().request.url);
        expect(Object.fromEntries(url.searchParams)).toEqual({ page: "1", size: "20", sort: "createdAt,desc" });
    });
});

describe("ApiError", () => {
    it("is what a failed call rejects with, so callers can branch on the code", async () => {
        reply = () =>
            new Response(JSON.stringify({ success: false, message: "Gone", error: "NOT_FOUND", statusCode: 404 }), {
                status: 404,
                headers: { "Content-Type": "application/json" },
            });
        const clients = createApiClients(browserTransport({ baseUrl: "http://localhost/bff" }));

        const error: unknown = await unreadCount(clients).catch((thrown: unknown) => thrown);

        expect(error).toBeInstanceOf(ApiError);
        expect((error as ApiError).is("NOT_FOUND")).toBe(true);
    });
});
