import { UnexpectedResponseError } from "@/shared/domain/errors";
import type { Page } from "@/shared/domain/page";
import { z } from "zod";
import { CORRELATION_ID_HEADER } from "./headers";

export interface ClientResult {
    readonly data?: unknown;
    readonly response: Response;
}

const apiResponseSchema = z.object({
    success: z.literal(true),
    message: z.string(),
    data: z.unknown(),
});

const pageResponseSchema = z.object({
    content: z.array(z.unknown()),
    page: z.number().int().nonnegative(),
    size: z.number().int().nonnegative(),
    totalElements: z.number().int().nonnegative(),
    totalPages: z.number().int().nonnegative(),
    first: z.boolean(),
    last: z.boolean(),
});

function contractError(response: Response, detail: string): UnexpectedResponseError {
    const path = response.url ? ` from ${new URL(response.url).pathname}` : "";
    return new UnexpectedResponseError(
        response.status,
        `${response.status.toString()} response${path} broke its contract: ${detail}`,
        response.headers.get(CORRELATION_ID_HEADER) ?? undefined,
    );
}

function describe(error: z.ZodError): string {
    return error.issues.map((issue) => `${issue.path.join(".") || "(root)"}: ${issue.message}`).join("; ");
}

function parse<S extends z.ZodType>(schema: S, value: unknown, response: Response, at: string): z.output<S> {
    const result = schema.safeParse(value);
    if (!result.success) throw contractError(response, `${at} ${describe(result.error)}`);
    return result.data;
}

function envelopeData({ data, response }: ClientResult): unknown {
    return parse(apiResponseSchema, data, response, "envelope").data;
}

export function unwrap<S extends z.ZodType>(result: ClientResult, schema: S): z.output<S> {
    return parse(schema, envelopeData(result), result.response, "data");
}

export function unwrapPage<S extends z.ZodType, D>(
    result: ClientResult,
    itemSchema: S,
    map: (item: z.output<S>) => D,
): Page<D> {
    const page = parse(pageResponseSchema, envelopeData(result), result.response, "page");
    const items = page.content.map((item, index) =>
        map(parse(itemSchema, item, result.response, `content.${index.toString()}`)),
    );
    return {
        items,
        page: page.page,
        size: page.size,
        totalItems: page.totalElements,
        totalPages: page.totalPages,
        isFirst: page.first,
        isLast: page.last,
    };
}

export function expectNoContent({ response }: ClientResult): void {
    if (response.status !== 204) throw contractError(response, "expected 204 No Content");
}
