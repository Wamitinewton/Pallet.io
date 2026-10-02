import { browserTransport } from "@/shared/infrastructure/api/browser-transport";
import { createApiClients, type ApiClients } from "@/shared/infrastructure/api/clients";
import { TEST_ORIGIN } from "./msw/server";

export function testApiClients(): ApiClients {
    return createApiClients(browserTransport({ baseUrl: `${TEST_ORIGIN}/bff` }));
}
