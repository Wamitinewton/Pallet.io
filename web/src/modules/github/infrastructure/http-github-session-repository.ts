import { ApiError } from "@/shared/domain/errors";
import { asInstallationId } from "@/shared/domain/ids";
import { asIsoInstant } from "@/shared/domain/instant";
import type { GitIntegrationClient } from "@/shared/infrastructure/api/clients";
import { expectNoContent, unwrap, unwrapPage } from "@/shared/infrastructure/api/envelope";
import { toPageQuery } from "@/shared/infrastructure/api/page-query";
import { z } from "zod";
import type { GitHubSessionRepository } from "../application/ports";
import { GITHUB_SESSION_NOT_FOUND } from "../domain/github-errors";
import type { GitHubSession } from "../domain/github-session";
import type { VisibleInstallation } from "../domain/installation";
import { accountSchema, githubUrlSchema, instantSchema } from "./github-dto";

const sessionSchema = z.object({ githubLogin: z.string().min(1), expiresAt: instantSchema });

const authorizationStartSchema = z.object({ authorizeUrl: githubUrlSchema, expiresAt: instantSchema });

const visibleInstallationSchema = z.object({ ...accountSchema, suspended: z.boolean() });

function toSession({ githubLogin, expiresAt }: z.output<typeof sessionSchema>): GitHubSession {
    return { githubLogin, expiresAt: asIsoInstant(expiresAt) };
}

function toVisibleInstallation(dto: z.output<typeof visibleInstallationSchema>): VisibleInstallation {
    return { ...dto, installationId: asInstallationId(dto.installationId) };
}

export function httpGitHubSessionRepository(gitIntegration: GitIntegrationClient): GitHubSessionRepository {
    return {
        async startAuthorization() {
            const { authorizeUrl, expiresAt } = unwrap(
                await gitIntegration.POST("/github/authorizations"),
                authorizationStartSchema,
            );
            return { url: authorizeUrl, expiresAt: asIsoInstant(expiresAt) };
        },

        async completeAuthorization({ code, state }) {
            return toSession(
                unwrap(
                    await gitIntegration.POST("/github/authorizations/complete", { body: { code, state } }),
                    sessionSchema,
                ),
            );
        },

        async find() {
            try {
                return toSession(unwrap(await gitIntegration.GET("/github/session"), sessionSchema));
            } catch (error) {
                if (error instanceof ApiError && error.is(GITHUB_SESSION_NOT_FOUND)) return null;
                throw error;
            }
        },

        async end() {
            expectNoContent(await gitIntegration.DELETE("/github/session"));
        },

        async visibleInstallations(page) {
            return unwrapPage(
                await gitIntegration.GET("/github/installations", { params: { query: toPageQuery(page) } }),
                visibleInstallationSchema,
                toVisibleInstallation,
            );
        },
    };
}
