import type { ComponentProps, ReactNode } from "react";
import { cx } from "../cx";
import styles from "./Page.module.css";

export type PageProps = ComponentProps<"div"> & {
    narrow?: boolean;
};

export function Page({ narrow = false, className, ...props }: PageProps) {
    return <div className={cx(styles.page, narrow && styles.narrow, className)} {...props} />;
}

export function PageNarrow(props: Omit<PageProps, "narrow">) {
    return <Page narrow {...props} />;
}

export type PageHeadProps = Omit<ComponentProps<"header">, "title"> & {
    title: ReactNode;
    description?: ReactNode;
    actions?: ReactNode;
};

export function PageHead({ title, description, actions, className, ...props }: PageHeadProps) {
    return (
        <header className={cx(styles.head, className)} {...props}>
            <div>
                <h1>{title}</h1>
                {description && <p className={styles.description}>{description}</p>}
            </div>
            {actions && <div className={styles.actions}>{actions}</div>}
        </header>
    );
}

export function SectionGap({ className, ...props }: ComponentProps<"div">) {
    return <div className={cx(styles.sectionGap, className)} {...props} />;
}

export function SettingsGrid({ className, ...props }: ComponentProps<"div">) {
    return <div className={cx(styles.settingsGrid, className)} {...props} />;
}

export type SubNavProps = ComponentProps<"nav"> & {
    "aria-label": string;
};

export function SubNav({ className, ...props }: SubNavProps) {
    return <nav className={cx(styles.subnav, className)} {...props} />;
}
