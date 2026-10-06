"use client";

import { IdentityUseCasesProvider } from "@/modules/identity";
import { InviteUseCasesProvider } from "@/modules/invites";
import { MemberUseCasesProvider } from "@/modules/members";
import { NotificationUseCasesProvider } from "@/modules/notifications";
import { OrganizationUseCasesProvider } from "@/modules/organizations";
import { SessionActionsProvider, StepUpProvider, type SessionActions, type SessionGateway } from "@/modules/session";
import { makeConfirmPassword } from "@/modules/session/application/confirm-password";
import { makeReadSessionSummary } from "@/modules/session/application/read-session-summary";
import { makeSignIn } from "@/modules/session/application/sign-in";
import { makeSignOut } from "@/modules/session/application/sign-out";
import { httpSessionGateway } from "@/modules/session/infrastructure/http-session-gateway";
import { TeamUseCasesProvider } from "@/modules/teams";
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
    return {
        signIn: makeSignIn(gateway),
        signOut: makeSignOut(gateway),
        readSummary: makeReadSessionSummary(gateway),
        confirmPassword: makeConfirmPassword(gateway),
    };
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
                <StepUpProvider>
                    <IdentityUseCasesProvider value={useCases.identity}>
                        <OrganizationUseCasesProvider value={useCases.organizations}>
                            <MemberUseCasesProvider value={useCases.members}>
                                <InviteUseCasesProvider value={useCases.invites}>
                                    <TeamUseCasesProvider value={useCases.teams}>
                                        <NotificationUseCasesProvider value={useCases.notifications}>
                                            {children}
                                        </NotificationUseCasesProvider>
                                    </TeamUseCasesProvider>
                                </InviteUseCasesProvider>
                            </MemberUseCasesProvider>
                        </OrganizationUseCasesProvider>
                    </IdentityUseCasesProvider>
                </StepUpProvider>
            </SessionActionsProvider>
        </UseCasesContext>
    );
}

export function useUseCases(): UseCases {
    const useCases = use(UseCasesContext);
    if (useCases === null) throw new Error("useUseCases() must be called inside <UseCasesProvider>");
    return useCases;
}
