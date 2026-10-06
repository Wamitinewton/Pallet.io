"use client";

import { usePathname } from "next/navigation";
import { useState, type ReactNode } from "react";
import styles from "./AppShell.module.css";
import { BreadcrumbsProvider } from "./Breadcrumbs";
import { MobileNav } from "./MobileNav";
import { Topbar } from "./Topbar";

export const MAIN_CONTENT_ID = "main-content";

export interface AppShellProps {
    readonly sidebar: ReactNode;
    readonly actions: ReactNode;
    readonly children: ReactNode;
}

export function AppShell({ sidebar, actions, children }: AppShellProps) {
    const pathname = usePathname();
    const [navigationOpenedOn, setNavigationOpenedOn] = useState<string | null>(null);

    return (
        <BreadcrumbsProvider>
            <a href={`#${MAIN_CONTENT_ID}`} className={styles.skip}>
                Skip to content
            </a>
            <div className={styles.app}>
                <aside className={styles.sidebar}>{sidebar}</aside>
                <div className={styles.main}>
                    <Topbar
                        navigationToggle={
                            <MobileNav
                                open={navigationOpenedOn === pathname}
                                onOpenChange={(open) => {
                                    setNavigationOpenedOn(open ? pathname : null);
                                }}
                            >
                                {sidebar}
                            </MobileNav>
                        }
                        actions={actions}
                    />
                    <main id={MAIN_CONTENT_ID} tabIndex={-1} className={styles.content}>
                        {children}
                    </main>
                </div>
            </div>
        </BreadcrumbsProvider>
    );
}
