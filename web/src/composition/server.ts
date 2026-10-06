import "server-only";

import { LAST_ORGANIZATION_COOKIE } from "@/modules/organizations";
import { signInPath, type ReadSessionSummary, type ResolvedSession } from "@/modules/session";
import { summarize } from "@/modules/session/domain/session";
import { requestOriginFrom } from "@/modules/session/infrastructure/request-origin";
import { revocationAwareFetch } from "@/modules/session/infrastructure/revocation-aware-fetch";
import { anonymousAccessTokens, type AccessTokenProvider } from "@/shared/application/access-token";
import { systemClock } from "@/shared/domain/clock";
import { SessionExpiredError } from "@/shared/domain/errors";
import { createApiClients } from "@/shared/infrastructure/api/clients";
import { serverTransport } from "@/shared/infrastructure/api/server-transport";
import { serverEnv } from "@/shared/infrastructure/config/server-env";
import { httpFetch } from "@/shared/infrastructure/http/http-fetch";
import { REQUESTED_PATH_HEADER } from "@/shared/infrastructure/http/requested-path";
import type { Route } from "next";
import { cookies, headers } from "next/headers";
import { redirect } from "next/navigation";
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

/** The server side of `GET /api/session`, so a page can prefetch what the browser reads from it. */
export const readServerSessionSummary: ReadSessionSummary = async () => {
    const resolved = await getCurrentSession();
    if (resolved.status !== "active") throw new SessionExpiredError();
    return summarize(resolved.session);
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

/** For public pages: never carries a session, so a dead one can't fail a read that needs none. */
export const getPublicServerUseCases = cache((): UseCases =>
    makeUseCases({
        clients: createApiClients(
            serverTransport({ gatewayUrl: serverEnv().PALLET_GATEWAY_URL, tokens: anonymousAccessTokens }),
        ),
        clock: systemClock,
    }),
);

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

export function sessionReauthenticate(request: Request): Promise<Response> {
    return sessionRuntime().reauthenticate(request);
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

/** Sends a signed-in page whose session is gone to sign in, with the way back to the page it asked for. */
export async function redirectToSignIn(): Promise<never> {
    const next = (await headers()).get(REQUESTED_PATH_HEADER) ?? undefined;
    redirect(signInPath({ reason: "expired", next }) as Route);
}

export async function requireActiveSession(): Promise<void> {
    if ((await getCurrentSession()).status !== "active") await redirectToSignIn();
}

/** A failed server read goes to the nearest error boundary, unless the session ended mid-render. */
export async function rethrowServerFailure(error: unknown): Promise<never> {
    if (error instanceof SessionExpiredError) await redirectToSignIn();
    throw error;
}

export async function lastOrganizationId(): Promise<string | undefined> {
    return (await cookies()).get(LAST_ORGANIZATION_COOKIE)?.value;
}
