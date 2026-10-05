import type { ApiError, FieldError } from "@/shared/domain/errors";
import { CORRELATION_ID_HEADER, RETRY_AFTER_HEADER } from "../api/headers";

export interface ErrorResponseOptions {
    readonly path: string;
    readonly correlationId?: string | undefined;
    readonly headers?: HeadersInit | undefined;
    readonly validationErrors?: readonly FieldError[] | undefined;
    readonly meta?: Readonly<Record<string, unknown>> | undefined;
}

/** The platform's `ErrorResponse` envelope, for errors the BFF itself decides or relays. */
export function errorResponse(status: number, code: string, message: string, options: ErrorResponseOptions): Response {
    const headers = new Headers(options.headers);
    headers.set("Cache-Control", "no-store");
    if (options.correlationId !== undefined) headers.set(CORRELATION_ID_HEADER, options.correlationId);
    const { validationErrors = [], meta = {} } = options;
    return Response.json(
        {
            success: false,
            message,
            error: code,
            statusCode: status,
            timestamp: new Date().toISOString(),
            path: options.path,
            ...(validationErrors.length > 0 && { validationErrors }),
            ...(Object.keys(meta).length > 0 && { meta }),
        },
        { status, headers },
    );
}

/** Relays an upstream `ErrorResponse` as it arrived: status, code, message, field errors, meta and `Retry-After`. */
export function apiErrorResponse(
    error: ApiError,
    options: Pick<ErrorResponseOptions, "path" | "correlationId">,
): Response {
    const retryAfter = error.retryAfterSeconds();
    return errorResponse(error.status, error.code, error.message, {
        path: options.path,
        correlationId: error.correlationId ?? options.correlationId,
        validationErrors: error.fieldErrors,
        meta: error.meta,
        headers: retryAfter === undefined ? undefined : { [RETRY_AFTER_HEADER]: String(retryAfter) },
    });
}

export function okResponse(data: unknown, message: string, headers?: HeadersInit): Response {
    const merged = new Headers(headers);
    merged.set("Cache-Control", "no-store");
    return Response.json({ success: true, message, data }, { headers: merged });
}
