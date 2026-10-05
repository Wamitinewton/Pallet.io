"use client";

import { IdentityUseCasesProvider } from "@/modules/identity";
import { SessionActionsProvider, type SessionActions, type SessionGateway } from "@/modules/session";
import { makeSignIn } from "@/modules/session/application/sign-in";
import { makeSignOut } from "@/modules/session/application/sign-out";
import { httpSessionGateway } from "@/modules/session/infrastructure/http-session-gateway";
import { browserTransport } from "@/shared/infrastructure/api/browser-transport";
import { createApiClients } from "@/shared/infrastructure/api/clients";
import type { Transport } from "@/shared/infrastructure/api/transport";
import { httpFetch } from "@/shared/infrastructure/http/http-fetch";
import { useClock } from "@/shared/presentation/providers";
import { createContext, use, useState, type ReactNode } from "react";
import { makeUseCases, type UseCases } from "./use-cases";

const UseCasesContext = createContext<UseCases | null>(null);

export interface UseCasesProviderProps {
    readonly children: ReactNode;
    readonly transport?: Transport;
    readonly sessionGateway?: SessionGateway;
}

function makeSessionActions(gateway: SessionGateway): SessionActions {
    return { signIn: makeSignIn(gateway), signOut: makeSignOut(gateway) };
}

export function UseCasesProvider({ children, transport, sessionGateway }: UseCasesProviderProps) {
    const clock = useClock();
    const [useCases] = useState(() =>
        makeUseCases({ clients: createApiClients(transport ?? browserTransport()), clock }),
    );
    const [sessionActions] = useState(() =>
        makeSessionActions(sessionGateway ?? httpSessionGateway({ fetch: httpFetch })),
    );
    return (
        <UseCasesContext value={useCases}>
            <SessionActionsProvider value={sessionActions}>
                <IdentityUseCasesProvider value={useCases.identity}>{children}</IdentityUseCasesProvider>
            </SessionActionsProvider>
        </UseCasesContext>
    );
}

export function useUseCases(): UseCases {
    const useCases = use(UseCasesContext);
    if (useCases === null) throw new Error("useUseCases() must be called inside <UseCasesProvider>");
    return useCases;
}
