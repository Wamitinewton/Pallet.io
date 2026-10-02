"use client";

import { ThemeProvider as NextThemesProvider } from "next-themes";
import type { ReactNode } from "react";

export const THEME_STORAGE_KEY = "pallet-theme";

export function ThemeProvider({ children }: Readonly<{ children: ReactNode }>) {
    return (
        <NextThemesProvider
            attribute="data-theme"
            defaultTheme="system"
            enableSystem
            disableTransitionOnChange
            storageKey={THEME_STORAGE_KEY}
        >
            {children}
        </NextThemesProvider>
    );
}
