import type { Clock } from "@/shared/domain/clock";
import type { ApiClients } from "@/shared/infrastructure/api/clients";

export interface UseCaseDependencies {
    readonly clients: ApiClients;
    readonly clock: Clock;
}

export type UseCases = Readonly<Record<string, never>>;

export function makeUseCases(_dependencies: UseCaseDependencies): UseCases {
    return {};
}
