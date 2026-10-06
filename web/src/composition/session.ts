import "server-only";

import { makeEndSession, type EndSession } from "@/modules/session/application/end-session";
import type { SessionStore, TokenIssuer } from "@/modules/session/application/ports";
import { makeReauthenticate } from "@/modules/session/application/reauthenticate";
import { makeReplaceTokens, type ReplaceTokens } from "@/modules/session/application/replace-tokens";
import { makeResolveSession, type ResolveSession } from "@/modules/session/application/resolve-session";
import { makeStartSession, type StartSession } from "@/modules/session/application/start-session";
import { createGatewayProxy, type GatewayProxy } from "@/modules/session/infrastructure/gateway-proxy";
import { identityTokenIssuer } from "@/modules/session/infrastructure/identity-token-issuer";
import { createLoginHandler } from "@/modules/session/infrastructure/login-handler";
import { createLogoutHandler } from "@/modules/session/infrastructure/logout-handler";
import { createReauthenticateHandler } from "@/modules/session/infrastructure/reauthenticate-handler";
import { lazyRedisConnection, type RedisConnection } from "@/modules/session/infrastructure/redis-client";
import { redisSessionStore } from "@/modules/session/infrastructure/redis-session-store";
import { redisRefreshLock } from "@/modules/session/infrastructure/refresh-lock";
import { createSessionCipher } from "@/modules/session/infrastructure/session-cipher";
import { sessionCookie, type SessionCookie } from "@/modules/session/infrastructure/session-cookie";
import { randomSessionIds } from "@/modules/session/infrastructure/session-id-generator";
import { createSessionSummaryHandler } from "@/modules/session/infrastructure/session-summary";
import { anonymousAccessTokens } from "@/shared/application/access-token";
import { systemClock, type Clock } from "@/shared/domain/clock";
import { createApiClients } from "@/shared/infrastructure/api/clients";
import { serverTransport } from "@/shared/infrastructure/api/server-transport";
import { serverEnv, type ServerEnv } from "@/shared/infrastructure/config/server-env";
import { httpFetch } from "@/shared/infrastructure/http/http-fetch";
import { originOf } from "@/shared/infrastructure/http/same-origin";
import { createLogger, type Logger } from "@/shared/infrastructure/logging/logger";

export interface SessionRuntime {
    readonly cookie: SessionCookie;
    readonly store: SessionStore;
    readonly issuer: TokenIssuer;
    readonly resolveSession: ResolveSession;
    readonly startSession: StartSession;
    readonly endSession: EndSession;
    readonly replaceTokens: ReplaceTokens;
    readonly gatewayProxy: GatewayProxy;
    readonly sessionSummary: (request: Request) => Promise<Response>;
    readonly login: (request: Request) => Promise<Response>;
    readonly logout: (request: Request) => Promise<Response>;
    readonly reauthenticate: (request: Request) => Promise<Response>;
    readonly logger: Logger;
}

export interface SessionRuntimeOverrides {
    readonly clock?: Clock;
    readonly redis?: RedisConnection;
    readonly logger?: Logger;
}

const sleep = (millis: number) => new Promise<void>((resolve) => setTimeout(resolve, millis));

export function buildSessionRuntime(env: ServerEnv, overrides: SessionRuntimeOverrides = {}): SessionRuntime {
    const clock = overrides.clock ?? systemClock;
    const logger = overrides.logger ?? createLogger(env.LOG_LEVEL);
    const redis = overrides.redis ?? lazyRedisConnection(env.PALLET_REDIS_URL, logger);
    const cookie = sessionCookie(env.PALLET_PUBLIC_BASE_URL);
    const publicOrigin = originOf(env.PALLET_PUBLIC_BASE_URL);
    const trustedProxyHops = env.PALLET_TRUSTED_PROXY_HOPS;

    const store = redisSessionStore({
        redis,
        cipher: createSessionCipher({
            currentKeyId: env.PALLET_SESSION_ENCRYPTION_KEY_ID,
            currentKey: env.PALLET_SESSION_ENCRYPTION_KEY,
            retiredKeys: env.PALLET_SESSION_RETIRED_ENCRYPTION_KEYS,
        }),
    });
    const issuer = identityTokenIssuer(
        createApiClients(serverTransport({ gatewayUrl: env.PALLET_GATEWAY_URL, tokens: anonymousAccessTokens }))
            .identity,
    );
    const replaceTokens = makeReplaceTokens({ store, clock });
    const resolveSession = makeResolveSession({
        store,
        issuer,
        lock: redisRefreshLock({ redis }),
        replaceTokens,
        clock,
        sleep,
    });

    const startSession = makeStartSession({ store, issuer, ids: randomSessionIds, clock });
    const endSession = makeEndSession({ store, issuer, resolveSession });

    return {
        cookie,
        store,
        issuer,
        resolveSession,
        replaceTokens,
        startSession,
        endSession,
        gatewayProxy: createGatewayProxy({
            gatewayUrl: env.PALLET_GATEWAY_URL,
            publicOrigin,
            trustedProxyHops,
            cookie,
            resolveSession,
            store,
            clock,
            logger,
            fetch: httpFetch,
        }),
        sessionSummary: createSessionSummaryHandler({ cookie, resolveSession, trustedProxyHops, clock }),
        login: createLoginHandler({ cookie, startSession, publicOrigin, trustedProxyHops, clock, logger }),
        logout: createLogoutHandler({ cookie, endSession, publicOrigin, trustedProxyHops, logger }),
        reauthenticate: createReauthenticateHandler({
            cookie,
            resolveSession,
            reauthenticate: makeReauthenticate({ store, issuer, replaceTokens }),
            publicOrigin,
            trustedProxyHops,
            clock,
            logger,
        }),
        logger,
    };
}

let runtime: SessionRuntime | undefined;

export function sessionRuntime(): SessionRuntime {
    runtime ??= buildSessionRuntime(serverEnv());
    return runtime;
}
