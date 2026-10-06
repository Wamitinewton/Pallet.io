import type { ReactNode } from "react";
import { ThemeToggle } from "../ui/theme/ThemeToggle";
import { Breadcrumbs } from "./Breadcrumbs";
import styles from "./Topbar.module.css";

export interface TopbarProps {
    readonly navigationToggle: ReactNode;
    readonly actions: ReactNode;
}

export function Topbar({ navigationToggle, actions }: TopbarProps) {
    return (
        <header className={styles.topbar}>
            {navigationToggle}
            <Breadcrumbs />
            <div className={styles.actions}>
                <ThemeToggle />
                {actions}
            </div>
        </header>
    );
}
