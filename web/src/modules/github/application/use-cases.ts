import type { Clock } from "@/shared/domain/clock";
import { makeCompleteAuthorization } from "./complete-authorization";
import { makeEndGitHubSession, type EndGitHubSession } from "./end-github-session";
import { makeFinishGitHubRedirect, type FinishGitHubRedirect } from "./finish-github-redirect";
import { makeGetGitHubSession, type GetGitHubSession } from "./get-github-session";
import { makeLinkInstallation, type LinkInstallation } from "./link-installation";
import { makeListOrgInstallations, type ListOrgInstallations } from "./list-org-installations";
import { makeListVisibleInstallations, type ListVisibleInstallations } from "./list-visible-installations";
import type { GitHubSessionRepository, InstallationRepository, ReturnIntentStore } from "./ports";
import { makeRetryGitHubRedirect, type RetryGitHubRedirect } from "./retry-github-redirect";
import { makeStartAuthorization, type StartAuthorization } from "./start-authorization";
import { makeStartInstall, type StartInstall } from "./start-install";
import { makeUnlinkInstallation, type UnlinkInstallation } from "./unlink-installation";

export interface GitHubUseCases {
    readonly startAuthorization: StartAuthorization;
    readonly getGitHubSession: GetGitHubSession;
    readonly endGitHubSession: EndGitHubSession;
    readonly listVisibleInstallations: ListVisibleInstallations;
    readonly startInstall: StartInstall;
    readonly linkInstallation: LinkInstallation;
    readonly listOrgInstallations: ListOrgInstallations;
    readonly unlinkInstallation: UnlinkInstallation;
    readonly finishGitHubRedirect: FinishGitHubRedirect;
    readonly retryGitHubRedirect: RetryGitHubRedirect;
}

export interface GitHubDependencies {
    readonly sessions: GitHubSessionRepository;
    readonly installations: InstallationRepository;
    readonly intents: ReturnIntentStore;
    readonly clock: Clock;
}

export function makeGitHubUseCases({ sessions, installations, intents, clock }: GitHubDependencies): GitHubUseCases {
    const startAuthorization = makeStartAuthorization(sessions, intents, clock);
    const startInstall = makeStartInstall(installations, intents, clock);
    const linkInstallation = makeLinkInstallation(installations);

    return {
        startAuthorization,
        getGitHubSession: makeGetGitHubSession(sessions),
        endGitHubSession: makeEndGitHubSession(sessions),
        listVisibleInstallations: makeListVisibleInstallations(sessions),
        startInstall,
        linkInstallation,
        listOrgInstallations: makeListOrgInstallations(installations),
        unlinkInstallation: makeUnlinkInstallation(installations),
        finishGitHubRedirect: makeFinishGitHubRedirect({
            completeAuthorization: makeCompleteAuthorization(sessions),
            linkInstallation,
            startAuthorization,
            intents,
            clock,
        }),
        retryGitHubRedirect: makeRetryGitHubRedirect(startAuthorization, startInstall),
    };
}
