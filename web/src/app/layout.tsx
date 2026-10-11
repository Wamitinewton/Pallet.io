import { UseCasesProvider } from "@/composition/client";
import { siteUrl } from "@/composition/site";
import { QueryProvider, ThemeProvider } from "@/shared/presentation/providers";
import { Toaster } from "@/shared/presentation/ui";
import type { Metadata, Viewport } from "next";
import { NuqsAdapter } from "nuqs/adapters/next/app";
import type { ReactNode } from "react";
import { fontVariables } from "./fonts";
import "./globals.css";
import { BRAND_COLORS, SITE_DESCRIPTION, SITE_NAME } from "./site";

export const metadata: Metadata = {
    metadataBase: siteUrl(),
    applicationName: SITE_NAME,
    title: {
        default: SITE_NAME,
        template: `%s · ${SITE_NAME}`,
    },
    description: SITE_DESCRIPTION,
    openGraph: { type: "website", siteName: SITE_NAME, locale: "en_US" },
    twitter: { card: "summary_large_image" },
};

export const viewport: Viewport = {
    colorScheme: "light dark",
    themeColor: [
        { media: "(prefers-color-scheme: light)", color: BRAND_COLORS.paper },
        { media: "(prefers-color-scheme: dark)", color: BRAND_COLORS.night },
    ],
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
