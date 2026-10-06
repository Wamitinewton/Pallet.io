import { UnexpectedResponseError } from "@/shared/domain/errors";
import { asUserId } from "@/shared/domain/ids";
import { asIsoInstant } from "@/shared/domain/instant";
import { errorFromFetchFailure, errorFromResponse } from "@/shared/infrastructure/api/error-mapping";
import { CORRELATION_ID_HEADER, CSRF_HEADER, CSRF_HEADER_VALUE } from "@/shared/infrastructure/api/headers";
import { z } from "zod";
import type { SessionGateway } from "../application/ports";
import type { SessionSummary } from "../domain/session";

export const SESSION_API_BASE_PATH = "/api/session";

export interface HttpSessionGatewayOptions {
    readonly fetch: typeof fetch;
    readonly baseUrl?: string;
}

const summaryEnvelopeSchema = z.object({
    success: z.literal(true),
    data: z.object({
        userId: z.string().min(1),
        email: z.string(),
        keycloakSessionId: z.string().min(1).optional(),
        accessExpiresAt: z.iso.datetime({ offset: true }),
    }),
});

function toSummary(body: unknown, response: Response): SessionSummary {
    const parsed = summaryEnvelopeSchema.safeParse(body);
    if (!parsed.success) {
        throw new UnexpectedResponseError(
            response.status,
            "The session summary broke its contract",
            response.headers.get(CORRELATION_ID_HEADER) ?? undefined,
        );
    }
    const { userId, email, keycloakSessionId, accessExpiresAt } = parsed.data.data;
    return {
        userId: asUserId(userId),
        email,
        keycloakSessionId,
        accessExpiresAt: asIsoInstant(accessExpiresAt),
    };
}

export function httpSessionGateway({
    fetch: send,
    baseUrl = SESSION_API_BASE_PATH,
}: HttpSessionGatewayOptions): SessionGateway {
    const request = async (method: "GET" | "POST", path: string, body?: unknown): Promise<Response> => {
        const headers = new Headers({ [CSRF_HEADER]: CSRF_HEADER_VALUE, Accept: "application/json" });
        if (body !== undefined) headers.set("Content-Type", "application/json");

        let response: Response;
        try {
            response = await send(`${baseUrl}${path}`, {
                method,
                headers,
                credentials: "same-origin",
                cache: "no-store",
                ...(body !== undefined && { body: JSON.stringify(body) }),
            });
        } catch (error) {
            throw errorFromFetchFailure(error);
        }
        if (!response.ok) throw await errorFromResponse(response);
        return response;
    };

    return {
        signIn: async (credentials) => {
            await request("POST", "/login", credentials);
        },
        signOut: async () => {
            await request("POST", "/logout");
        },
        reauthenticate: async (reauthentication) => {
            await request("POST", "/reauthenticate", reauthentication);
        },
        summary: async () => {
            const response = await request("GET", "");
            const body: unknown = await response.json().catch(() => undefined);
            return toSummary(body, response);
        },
    };
}
