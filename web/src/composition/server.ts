import "server-only";

import type { ResolvedSession } from "@/modules/session";
import { requestOriginFrom } from "@/modules/session/infrastructure/request-origin";
import { revocationAwareFetch } from "@/modules/session/infrastructure/revocation-aware-fetch";
import type { AccessTokenProvider } from "@/shared/application/access-token";
import { systemClock } from "@/shared/domain/clock";
import { SessionExpiredError } from "@/shared/domain/errors";
import { createApiClients } from "@/shared/infrastructure/api/clients";
import { serverTransport } from "@/shared/infrastructure/api/server-transport";
import { serverEnv } from "@/shared/infrastructure/config/server-env";
import { httpFetch } from "@/shared/infrastructure/http/http-fetch";
import { cookies, headers } from "next/headers";
import { cache } from "react";
import { sessionRuntime } from "./session";
import { makeUseCases, type UseCases } from "./use-cases";

export const getCurrentSession = cache(async (): Promise<ResolvedSession> => {
    const { cookie, resolveSession } = sessionRuntime();
    const [cookieStore, headerList] = await Promise.all([cookies(), headers()]);
    return resolveSession(
        cookieStore.get(cookie.name)?.value,
        requestOriginFrom(headerList, serverEnv().PALLET_TRUSTED_PROXY_HOPS),
    );
});

const sessionAccessTokens: AccessTokenProvider = {
    async accessToken() {
        const resolved = await getCurrentSession();
        if (resolved.status === "expired") throw new SessionExpiredError();
        return resolved.status === "active" ? resolved.session.accessToken : undefined;
    },
};

async function discardCurrentSession(): Promise<void> {
    const resolved = await getCurrentSession();
    if (resolved.status === "active") await sessionRuntime().store.delete(resolved.session.id);
}

export const getServerUseCases = cache((): UseCases => {
    const transport = serverTransport({
        gatewayUrl: serverEnv().PALLET_GATEWAY_URL,
        tokens: sessionAccessTokens,
        fetch: revocationAwareFetch(discardCurrentSession, httpFetch),
    });
    return makeUseCases({ clients: createApiClients(transport), clock: systemClock });
});

export function bffProxy(request: Request, segments: readonly string[]): Promise<Response> {
    return sessionRuntime().gatewayProxy(request, segments);
}

export function sessionSummary(request: Request): Promise<Response> {
    return sessionRuntime().sessionSummary(request);
}

export function sessionLogin(request: Request): Promise<Response> {
    return sessionRuntime().login(request);
}

export function sessionLogout(request: Request): Promise<Response> {
    return sessionRuntime().logout(request);
}

/** For pages a signed-in person should skip; an unreachable session store reads as signed out. */
export async function hasActiveSession(): Promise<boolean> {
    try {
        return (await getCurrentSession()).status === "active";
    } catch (error) {
        sessionRuntime().logger.warn("session.resolve_failed", { error });
        return false;
    }
}
