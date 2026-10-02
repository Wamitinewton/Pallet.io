import type { ComponentProps, ReactNode } from "react";
import { cx } from "../cx";
import { Icon, type IconName } from "../icons/Icon";
import styles from "./EmptyState.module.css";

export type EmptyStateProps = Omit<ComponentProps<"div">, "title"> & {
    icon: IconName;
    title: ReactNode;
    description?: ReactNode;
    actions?: ReactNode;
    headingLevel?: 2 | 3;
};

export function EmptyState({
    icon,
    title,
    description,
    actions,
    headingLevel = 2,
    className,
    ...props
}: EmptyStateProps) {
    const Heading = headingLevel === 2 ? "h2" : "h3";
    return (
        <div className={cx(styles.empty, className)} {...props}>
            <span className={styles.glyph}>
                <Icon name={icon} />
            </span>
            <Heading>{title}</Heading>
            {description && <p className={styles.description}>{description}</p>}
            {actions && <div className={styles.actions}>{actions}</div>}
        </div>
    );
}
