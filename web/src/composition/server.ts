import "server-only";

import { anonymousAccessTokens } from "@/shared/application/access-token";
import { systemClock } from "@/shared/domain/clock";
import { createApiClients } from "@/shared/infrastructure/api/clients";
import { serverTransport } from "@/shared/infrastructure/api/server-transport";
import { serverEnv } from "@/shared/infrastructure/config/server-env";
import { cache } from "react";
import { makeUseCases, type UseCases } from "./use-cases";

export const getServerUseCases = cache((): UseCases => {
    const transport = serverTransport({ gatewayUrl: serverEnv().PALLET_GATEWAY_URL, tokens: anonymousAccessTokens });
    return makeUseCases({ clients: createApiClients(transport), clock: systemClock });
});
