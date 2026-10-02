import {
    ApiError,
    isAppError,
    NetworkError,
    SessionExpiredError,
    UnexpectedResponseError,
    type AppError,
} from "@/shared/domain/errors";
import { z } from "zod";
import { CORRELATION_ID_HEADER, RETRY_AFTER_HEADER, SESSION_EXPIRED_CODE } from "./headers";

const errorResponseSchema = z.object({
    success: z.literal(false),
    message: z.string(),
    error: z.string().min(1),
    statusCode: z.number().int(),
    validationErrors: z.array(z.object({ field: z.string(), message: z.string() })).optional(),
    meta: z.record(z.string(), z.unknown()).optional(),
});

function parseJson(text: string): unknown {
    try {
        return JSON.parse(text);
    } catch {
        return undefined;
    }
}

function metaWithRetryAfter(meta: Record<string, unknown>, response: Response): Record<string, unknown> {
    const header = response.headers.get(RETRY_AFTER_HEADER);
    if (meta.retryAfter !== undefined || header === null) return meta;
    const seconds = Number(header);
    return Number.isFinite(seconds) ? { ...meta, retryAfter: seconds } : meta;
}

export async function errorFromResponse(response: Response): Promise<AppError> {
    const correlationId = response.headers.get(CORRELATION_ID_HEADER) ?? undefined;
    const text = await response.text().catch(() => "");
    const parsed = errorResponseSchema.safeParse(parseJson(text));

    if (!parsed.success) {
        return new UnexpectedResponseError(
            response.status,
            `Unexpected ${String(response.status)} response without an error envelope`,
            correlationId,
        );
    }

    const body = parsed.data;
    if (response.status === 401 && body.error === SESSION_EXPIRED_CODE) {
        return new SessionExpiredError(body.message);
    }
    return new ApiError(
        response.status,
        body.error,
        body.message,
        body.validationErrors ?? [],
        metaWithRetryAfter(body.meta ?? {}, response),
        correlationId,
    );
}

function isAbort(error: unknown): boolean {
    return error instanceof DOMException && error.name === "AbortError";
}

export function errorFromFetchFailure(error: unknown): Error {
    if (isAppError(error) || isAbort(error)) return error as Error;
    return new NetworkError(undefined, { cause: error });
}
