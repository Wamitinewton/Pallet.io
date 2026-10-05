import { errorFromFetchFailure, errorFromResponse } from "@/shared/infrastructure/api/error-mapping";
import { CSRF_HEADER, CSRF_HEADER_VALUE } from "@/shared/infrastructure/api/headers";
import type { SessionGateway } from "../application/ports";

export const SESSION_API_BASE_PATH = "/api/session";

export interface HttpSessionGatewayOptions {
    readonly fetch: typeof fetch;
    readonly baseUrl?: string;
}

export function httpSessionGateway({
    fetch: send,
    baseUrl = SESSION_API_BASE_PATH,
}: HttpSessionGatewayOptions): SessionGateway {
    const post = async (path: string, body?: unknown): Promise<void> => {
        const headers = new Headers({ [CSRF_HEADER]: CSRF_HEADER_VALUE, Accept: "application/json" });
        if (body !== undefined) headers.set("Content-Type", "application/json");

        let response: Response;
        try {
            response = await send(`${baseUrl}${path}`, {
                method: "POST",
                headers,
                credentials: "same-origin",
                cache: "no-store",
                ...(body !== undefined && { body: JSON.stringify(body) }),
            });
        } catch (error) {
            throw errorFromFetchFailure(error);
        }
        if (!response.ok) throw await errorFromResponse(response);
    };

    return {
        signIn: (credentials) => post("/login", credentials),
        signOut: () => post("/logout"),
    };
}
