import type { AppUseCases } from "@/modules/apps";
import { makeAppUseCases } from "@/modules/apps/application/use-cases";
import { httpAppRepository } from "@/modules/apps/infrastructure/http-app-repository";
import type { GitHubUseCases } from "@/modules/github";
import { makeGitHubUseCases } from "@/modules/github/application/use-cases";
import { httpGitHubSessionRepository } from "@/modules/github/infrastructure/http-github-session-repository";
import { httpInstallationRepository } from "@/modules/github/infrastructure/http-installation-repository";
import { sessionStorageReturnIntentStore } from "@/modules/github/infrastructure/session-storage-return-intent-store";
import type { IdentityUseCases } from "@/modules/identity";
import { makeIdentityUseCases } from "@/modules/identity/application/use-cases";
import { httpAccountRepository } from "@/modules/identity/infrastructure/http-account-repository";
import { httpPasswordRepository } from "@/modules/identity/infrastructure/http-password-repository";
import { httpSignupRepository } from "@/modules/identity/infrastructure/http-signup-repository";
import { httpVerificationRepository } from "@/modules/identity/infrastructure/http-verification-repository";
import type { InviteUseCases } from "@/modules/invites";
import { makeInviteUseCases } from "@/modules/invites/application/use-cases";
import { httpInviteAcceptanceRepository } from "@/modules/invites/infrastructure/http-invite-acceptance-repository";
import { httpInviteRepository } from "@/modules/invites/infrastructure/http-invite-repository";
import type { MemberUseCases } from "@/modules/members";
import { makeMemberUseCases } from "@/modules/members/application/use-cases";
import { httpMemberRepository } from "@/modules/members/infrastructure/http-member-repository";
import type { NotificationUseCases } from "@/modules/notifications";
import { makeNotificationUseCases } from "@/modules/notifications/application/use-cases";
import { httpNotificationRepository } from "@/modules/notifications/infrastructure/http-notification-repository";
import type { OrganizationUseCases } from "@/modules/organizations";
import { makeOrganizationUseCases } from "@/modules/organizations/application/use-cases";
import { httpOrganizationRepository } from "@/modules/organizations/infrastructure/http-organization-repository";
import type { TeamUseCases } from "@/modules/teams";
import { makeTeamUseCases } from "@/modules/teams/application/use-cases";
import { httpTeamRepository } from "@/modules/teams/infrastructure/http-team-repository";
import type { Clock } from "@/shared/domain/clock";
import type { ApiClients } from "@/shared/infrastructure/api/clients";

export interface UseCaseDependencies {
    readonly clients: ApiClients;
    readonly clock: Clock;
}

export interface UseCases {
    readonly identity: IdentityUseCases;
    readonly organizations: OrganizationUseCases;
    readonly members: MemberUseCases;
    readonly invites: InviteUseCases;
    readonly teams: TeamUseCases;
    readonly apps: AppUseCases;
    readonly github: GitHubUseCases;
    readonly notifications: NotificationUseCases;
}

export function makeUseCases({ clients, clock }: UseCaseDependencies): UseCases {
    return {
        identity: makeIdentityUseCases({
            signups: httpSignupRepository(clients.identity),
            verifications: httpVerificationRepository(clients.identity),
            passwords: httpPasswordRepository(clients.identity),
            accounts: httpAccountRepository(clients.identity),
        }),
        organizations: makeOrganizationUseCases({ organizations: httpOrganizationRepository(clients.orgTeam) }),
        members: makeMemberUseCases({ members: httpMemberRepository(clients.orgTeam) }),
        invites: makeInviteUseCases({
            invites: httpInviteRepository(clients.orgTeam),
            acceptance: httpInviteAcceptanceRepository(clients),
            clock,
        }),
        teams: makeTeamUseCases({ teams: httpTeamRepository(clients.orgTeam) }),
        apps: makeAppUseCases({ apps: httpAppRepository(clients.orgTeam) }),
        github: makeGitHubUseCases({
            sessions: httpGitHubSessionRepository(clients.gitIntegration),
            installations: httpInstallationRepository(clients.gitIntegration),
            intents: sessionStorageReturnIntentStore(),
            clock,
        }),
        notifications: makeNotificationUseCases({ notifications: httpNotificationRepository(clients.notification) }),
    };
}
