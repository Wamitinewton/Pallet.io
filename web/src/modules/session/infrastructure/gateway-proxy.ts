import type { Clock } from "@/shared/domain/clock";
import {
    CONFIRM_SLUG_HEADER,
    CORRELATION_ID_HEADER,
    IDEMPOTENCY_KEY_HEADER,
    RETRY_AFTER_HEADER,
} from "@/shared/infrastructure/api/headers";
import { traceparentFrom } from "@/shared/infrastructure/http/correlation";
import { errorResponse } from "@/shared/infrastructure/http/error-response";
import { CSRF_REJECTED_CODE, CSRF_REJECTED_MESSAGE, passesCsrfCheck } from "@/shared/infrastructure/http/same-origin";
import type { Logger } from "@/shared/infrastructure/logging/logger";
import type { SessionStore } from "../application/ports";
import type { ResolvedSession, ResolveSession } from "../application/resolve-session";
import { remainingLifetimeSeconds } from "../domain/session-policy";
import { requestOriginFrom, type ResolvedRequestOrigin } from "./request-origin";
import { isRevokedSessionAnswer, SESSION_EXPIRED_CODE, SESSION_EXPIRED_MESSAGE } from "./revocation";
import type { SessionCookie } from "./session-cookie";
import { sessionLogId } from "./session-keys";

const SERVICES = new Set(["identity", "org-team", "git-integration", "notification"]);
const SAFE_METHODS = new Set(["GET", "HEAD"]);
const NULL_BODY_STATUSES = new Set([101, 204, 205, 304]);
const FORWARDED_REQUEST_HEADERS = ["Accept", "Content-Type", IDEMPOTENCY_KEY_HEADER, CONFIRM_SLUG_HEADER];
const RETURNED_RESPONSE_HEADERS = ["Content-Type", RETRY_AFTER_HEADER];

export const MAX_BODY_BYTES = 1024 * 1024;
export const GATEWAY_TIMEOUT_MS = 15_000;

export interface GatewayProxyOptions {
    readonly gatewayUrl: string;
    readonly publicOrigin: string;
    readonly trustedProxyHops: number;
    readonly cookie: SessionCookie;
    readonly resolveSession: ResolveSession;
    readonly store: Pick<SessionStore, "delete">;
    readonly clock: Clock;
    readonly logger: Logger;
    readonly fetch: typeof fetch;
    readonly timeoutMs?: number;
    readonly maxBodyBytes?: number;
}

export type GatewayProxy = (request: Request, segments: readonly string[]) => Promise<Response>;

export function gatewayPathOf(segments: readonly string[]): string | undefined {
    const [service, ...rest] = segments;
    if (service === undefined || !SERVICES.has(service)) return undefined;
    if (segments.some((segment) => segment === "" || segment === "." || segment === "..")) return undefined;
    if (rest.some((segment, index) => segment === "v3" && rest[index + 1] === "api-docs")) return undefined;
    if (service === "git-integration" && rest[0] === "webhooks") return undefined;
    return segments.map(encodeURIComponent).join("/");
}

interface LimitedBody {
    readonly stream: ReadableStream<Uint8Array>;
    exceeded(): boolean;
}

function limited(body: ReadableStream<Uint8Array>, maxBytes: number): LimitedBody {
    let total = 0;
    const stream = body.pipeThrough(
        new TransformStream<Uint8Array, Uint8Array>({
            transform(chunk, controller) {
                total += chunk.byteLength;
                if (total > maxBytes) controller.error(new RangeError("Request body exceeds the size limit"));
                else controller.enqueue(chunk);
            },
        }),
    );
    return { stream, exceeded: () => total > maxBytes };
}

const causedBy = (error: unknown, name: string): boolean =>
    error instanceof Error && (error.name === name || causedBy(error.cause, name));

function forwardedHeaders(request: Request, origin: ResolvedRequestOrigin, accessToken: string | undefined): Headers {
    const headers = new Headers();
    for (const name of FORWARDED_REQUEST_HEADERS) {
        const value = request.headers.get(name);
        if (value !== null) headers.set(name, value);
    }
    headers.set(CORRELATION_ID_HEADER, origin.correlationId);
    if (accessToken !== undefined) headers.set("Authorization", `Bearer ${accessToken}`);
    if (origin.clientIp !== undefined) headers.set("X-Forwarded-For", origin.clientIp);
    const traceparent = traceparentFrom(request.headers);
    if (traceparent !== undefined) headers.set("traceparent", traceparent);
    return headers;
}

function returnedHeaders(upstream: Response, correlationId: string): Headers {
    const headers = new Headers({ "Cache-Control": "no-store" });
    for (const name of RETURNED_RESPONSE_HEADERS) {
        const value = upstream.headers.get(name);
        if (value !== null) headers.set(name, value);
    }
    headers.set(CORRELATION_ID_HEADER, upstream.headers.get(CORRELATION_ID_HEADER) ?? correlationId);
    return headers;
}

export function createGatewayProxy({
    gatewayUrl,
    publicOrigin,
    trustedProxyHops,
    cookie,
    resolveSession,
    store,
    clock,
    logger,
    fetch: send,
    timeoutMs = GATEWAY_TIMEOUT_MS,
    maxBodyBytes = MAX_BODY_BYTES,
}: GatewayProxyOptions): GatewayProxy {
    return async (request, segments) => {
        const path = new URL(request.url).pathname;
        const origin = requestOriginFrom(request.headers, trustedProxyHops);
        const { correlationId } = origin;
        const fail = (status: number, code: string, message: string, headers?: HeadersInit) =>
            errorResponse(status, code, message, { path, correlationId, ...(headers ? { headers } : {}) });

        const target = gatewayPathOf(segments);
        if (target === undefined) return fail(404, "NOT_FOUND", "No such endpoint");

        if (!SAFE_METHODS.has(request.method) && !passesCsrfCheck(request.headers, publicOrigin)) {
            return fail(403, CSRF_REJECTED_CODE, CSRF_REJECTED_MESSAGE);
        }

        const declaredLength = Number(request.headers.get("Content-Length") ?? "0");
        if (declaredLength > maxBodyBytes) return fail(413, "PAYLOAD_TOO_LARGE", "Request body is too large");

        const cookieValue = cookie.read(request.headers.get("Cookie"));

        let resolved: ResolvedSession;
        try {
            resolved = await resolveSession(cookieValue, origin);
        } catch (error) {
            logger.warn("session.resolve_failed", { correlationId, error });
            return fail(503, "SERVICE_UNAVAILABLE", "The session could not be refreshed. Try again.");
        }
        if (resolved.status === "expired") {
            return fail(401, SESSION_EXPIRED_CODE, SESSION_EXPIRED_MESSAGE, { "Set-Cookie": cookie.clear() });
        }
        const session = resolved.status === "active" ? resolved.session : undefined;

        const url = new URL(`${gatewayUrl}/api/v1/${target}`);
        url.search = new URL(request.url).search;
        const body =
            SAFE_METHODS.has(request.method) || request.body === null ? undefined : limited(request.body, maxBodyBytes);

        let upstream: Response;
        try {
            upstream = await send(url, {
                method: request.method,
                headers: forwardedHeaders(request, origin, session?.accessToken),
                body: body?.stream ?? null,
                redirect: "manual",
                signal: AbortSignal.any([request.signal, AbortSignal.timeout(timeoutMs)]),
                ...(body === undefined ? {} : { duplex: "half" }),
            });
        } catch (error) {
            if (body?.exceeded() === true) return fail(413, "PAYLOAD_TOO_LARGE", "Request body is too large");
            if (causedBy(error, "TimeoutError"))
                return fail(504, "GATEWAY_TIMEOUT", "The platform took too long to answer");
            logger.warn("bff.upstream_failed", { correlationId, error });
            return fail(502, "BAD_GATEWAY", "The platform could not be reached");
        }

        const responseHeaders = returnedHeaders(upstream, correlationId);

        if (session !== undefined && upstream.status === 401) {
            const text = await upstream.text();
            if (isRevokedSessionAnswer(upstream.status, text, true)) {
                await store.delete(session.id);
                logger.info("session.revoked_elsewhere", { session: sessionLogId(session.id), correlationId });
                return fail(401, SESSION_EXPIRED_CODE, SESSION_EXPIRED_MESSAGE, { "Set-Cookie": cookie.clear() });
            }
            return new Response(text, { status: upstream.status, headers: responseHeaders });
        }

        if (resolved.status === "active" && resolved.refreshed && cookieValue !== undefined) {
            responseHeaders.append(
                "Set-Cookie",
                cookie.issue(cookieValue, remainingLifetimeSeconds(resolved.session, clock)),
            );
        }

        return new Response(NULL_BODY_STATUSES.has(upstream.status) ? null : upstream.body, {
            status: upstream.status,
            headers: responseHeaders,
        });
    };
}
