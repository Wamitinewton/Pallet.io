"use client";

import { createContext, use, type ReactNode } from "react";

export interface UseCasesContextProviderProps<T> {
    readonly value: T;
    readonly children: ReactNode;
}

/**
 * A module's presentation declares the use cases it needs with this; `composition/client.tsx` supplies
 * them. Presentation never imports the composition root, so the dependency still points inward.
 */
export function createUseCasesContext<T>(name: string) {
    const Context = createContext<T | null>(null);

    function Provider({ value, children }: UseCasesContextProviderProps<T>) {
        return <Context value={value}>{children}</Context>;
    }

    function useValue(): T {
        const value = use(Context);
        if (value === null) throw new Error(`${name} is not provided: render it inside <UseCasesProvider>`);
        return value;
    }

    return [Provider, useValue] as const;
}
