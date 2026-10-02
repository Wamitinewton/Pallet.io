import type { components } from "@/shared/infrastructure/api/generated/org-team";
import { HttpResponse } from "msw";

type Schemas = components["schemas"];
type WirePage = Required<Omit<Schemas["PageResponseAppDto"], "content">>;
type WireError = Required<
    Pick<Schemas["ErrorResponse"], "success" | "message" | "error" | "statusCode" | "timestamp" | "path">
> &
    Pick<Schemas["ErrorResponse"], "validationErrors" | "meta">;

export const TEST_CORRELATION_ID = "corr-test-0001";
const TIMESTAMP = "2026-01-15T12:00:00Z";

const STATUS_TEXT: Readonly<Record<number, string>> = {
    400: "Bad request",
    401: "Unauthorized",
    403: "Forbidden",
    404: "Not found",
    409: "Conflict",
    410: "Gone",
    422: "Unprocessable",
    429: "Too many requests",
    500: "Internal server error",
    502: "Bad gateway",
    503: "Service unavailable",
};

export function okBody<T>(data: T, message = "OK"): { success: true; message: string; data: T } {
    return { success: true, message, data };
}

export function ok(data: unknown, message = "OK", init?: ResponseInit) {
    return HttpResponse.json(okBody(data, message), init);
}

export interface PageOptions {
    readonly page?: number;
    readonly size?: number;
    readonly totalElements?: number;
    readonly message?: string;
}

export function pageBody<T>(
    items: readonly T[],
    { page = 0, size = 20, totalElements = items.length }: PageOptions = {},
) {
    const totalPages = Math.ceil(totalElements / size);
    const meta: WirePage = {
        page,
        size,
        totalElements,
        totalPages,
        first: page === 0,
        last: page >= totalPages - 1,
    };
    return { content: [...items], ...meta };
}

export function page(items: readonly unknown[], options: PageOptions = {}) {
    return ok(pageBody(items, options), options.message ?? "OK");
}

export interface ErrorOptions {
    readonly message?: string;
    readonly path?: string;
    readonly validationErrors?: readonly { field: string; message: string }[];
    readonly meta?: Record<string, unknown>;
    readonly headers?: Record<string, string>;
}

export function errorBody(status: number, code: string, options: ErrorOptions = {}): WireError {
    const body: WireError = {
        success: false,
        message: options.message ?? STATUS_TEXT[status] ?? "Request failed",
        error: code,
        statusCode: status,
        timestamp: TIMESTAMP,
        path: options.path ?? "/api/v1/test",
    };
    if (options.validationErrors) body.validationErrors = [...options.validationErrors];
    if (options.meta) body.meta = options.meta;
    return body;
}

export function error(status: number, code: string, options: ErrorOptions = {}) {
    return HttpResponse.json(errorBody(status, code, options), {
        status,
        headers: { "X-Correlation-Id": TEST_CORRELATION_ID, ...options.headers },
    });
}
