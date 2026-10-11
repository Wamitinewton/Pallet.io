import type { ReactNode } from "react";
import styles from "./layout.module.css";
import { SiteFooter } from "./sections/SiteFooter";
import { SiteHeader } from "./sections/SiteHeader";

export default function MarketingLayout({ children }: Readonly<{ children: ReactNode }>) {
    return (
        <div className={styles.site}>
            <a className={styles.skip} href="#main">
                Skip to content
            </a>
            <SiteHeader />
            <main id="main" tabIndex={-1} className={styles.main}>
                {children}
            </main>
            <SiteFooter />
        </div>
    );
}
