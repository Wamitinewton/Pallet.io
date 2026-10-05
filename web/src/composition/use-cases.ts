import type { IdentityUseCases } from "@/modules/identity";
import { makeIdentityUseCases } from "@/modules/identity/application/use-cases";
import { httpPasswordRepository } from "@/modules/identity/infrastructure/http-password-repository";
import { httpSignupRepository } from "@/modules/identity/infrastructure/http-signup-repository";
import { httpVerificationRepository } from "@/modules/identity/infrastructure/http-verification-repository";
import type { Clock } from "@/shared/domain/clock";
import type { ApiClients } from "@/shared/infrastructure/api/clients";

export interface UseCaseDependencies {
    readonly clients: ApiClients;
    readonly clock: Clock;
}

export interface UseCases {
    readonly identity: IdentityUseCases;
}

export function makeUseCases({ clients }: UseCaseDependencies): UseCases {
    return {
        identity: makeIdentityUseCases({
            signups: httpSignupRepository(clients.identity),
            verifications: httpVerificationRepository(clients.identity),
            passwords: httpPasswordRepository(clients.identity),
        }),
    };
}
