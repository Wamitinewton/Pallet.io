import { UseCasesProvider } from "@/composition/client";
import { QueryProvider, ThemeProvider } from "@/shared/presentation/providers";
import { Toaster } from "@/shared/presentation/ui";
import type { Metadata, Viewport } from "next";
import { NuqsAdapter } from "nuqs/adapters/next/app";
import type { ReactNode } from "react";
import { fontVariables } from "./fonts";
import "./globals.css";

export const metadata: Metadata = {
    title: {
        default: "Pallet",
        template: "%s · Pallet",
    },
    description: "Push code, get a live URL with TLS.",
    icons: { icon: "/favicon.svg" },
};

export const viewport: Viewport = {
    colorScheme: "light dark",
};

export default function RootLayout({ children }: Readonly<{ children: ReactNode }>) {
    return (
        <html lang="en" className={fontVariables} suppressHydrationWarning>
            <body>
                <ThemeProvider>
                    <QueryProvider>
                        <UseCasesProvider>
                            <NuqsAdapter>
                                <Toaster>{children}</Toaster>
                            </NuqsAdapter>
                        </UseCasesProvider>
                    </QueryProvider>
                </ThemeProvider>
            </body>
        </html>
    );
}
