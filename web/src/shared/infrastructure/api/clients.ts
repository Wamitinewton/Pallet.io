import createClient, { type Client } from "openapi-fetch";
import type { paths as GitIntegrationPaths } from "./generated/git-integration";
import type { paths as IdentityPaths } from "./generated/identity";
import type { paths as NotificationPaths } from "./generated/notification";
import type { paths as OrgTeamPaths } from "./generated/org-team";
import { errorMiddleware, trimTrailingSlash, type Transport } from "./transport";

export type IdentityClient = Client<IdentityPaths>;
export type OrgTeamClient = Client<OrgTeamPaths>;
export type GitIntegrationClient = Client<GitIntegrationPaths>;
export type NotificationClient = Client<NotificationPaths>;

export interface ApiClients {
    readonly identity: IdentityClient;
    readonly orgTeam: OrgTeamClient;
    readonly gitIntegration: GitIntegrationClient;
    readonly notification: NotificationClient;
}

function serviceClient<Paths extends object>(transport: Transport, service: string): Client<Paths> {
    const client = createClient<Paths>({
        baseUrl: `${trimTrailingSlash(transport.baseUrl)}/${service}`,
        fetch: transport.fetch,
    });
    client.use(...transport.middleware, errorMiddleware);
    return client;
}

export function createApiClients(transport: Transport): ApiClients {
    return {
        identity: serviceClient<IdentityPaths>(transport, "identity"),
        orgTeam: serviceClient<OrgTeamPaths>(transport, "org-team"),
        gitIntegration: serviceClient<GitIntegrationPaths>(transport, "git-integration"),
        notification: serviceClient<NotificationPaths>(transport, "notification"),
    };
}
