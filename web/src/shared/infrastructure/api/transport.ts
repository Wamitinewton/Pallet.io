import type { Middleware } from "openapi-fetch";
import { errorFromFetchFailure, errorFromResponse } from "./error-mapping";

export interface Transport {
    readonly baseUrl: string;
    readonly fetch: (request: Request) => Promise<Response>;
    readonly middleware: readonly Middleware[];
}

export const errorMiddleware: Middleware = {
    async onResponse({ response }) {
        if (!response.ok) throw await errorFromResponse(response);
        return undefined;
    },
    onError({ error }) {
        return errorFromFetchFailure(error);
    },
};

export function trimTrailingSlash(url: string): string {
    return url.replace(/\/+$/, "");
}
