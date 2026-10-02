"use client";

import { browserTransport } from "@/shared/infrastructure/api/browser-transport";
import { createApiClients } from "@/shared/infrastructure/api/clients";
import type { Transport } from "@/shared/infrastructure/api/transport";
import { useClock } from "@/shared/presentation/providers";
import { createContext, use, useState, type ReactNode } from "react";
import { makeUseCases, type UseCases } from "./use-cases";

const UseCasesContext = createContext<UseCases | null>(null);

export interface UseCasesProviderProps {
    readonly children: ReactNode;
    readonly transport?: Transport;
}

export function UseCasesProvider({ children, transport }: UseCasesProviderProps) {
    const clock = useClock();
    const [useCases] = useState(() =>
        makeUseCases({ clients: createApiClients(transport ?? browserTransport()), clock }),
    );
    return <UseCasesContext value={useCases}>{children}</UseCasesContext>;
}

export function useUseCases(): UseCases {
    const useCases = use(UseCasesContext);
    if (useCases === null) throw new Error("useUseCases() must be called inside <UseCasesProvider>");
    return useCases;
}
