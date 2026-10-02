import type { ComponentProps, ReactNode } from "react";
import { cx } from "../cx";
import styles from "./Panel.module.css";

export type PanelProps = ComponentProps<"section"> & {
    tone?: "default" | "danger";
};

export function Panel({ tone = "default", className, ...props }: PanelProps) {
    return <section className={cx(styles.panel, tone === "danger" && styles.danger, className)} {...props} />;
}

export type PanelHeadProps = Omit<ComponentProps<"div">, "title"> & {
    title?: ReactNode;
    description?: ReactNode;
    headingLevel?: 2 | 3;
};

export function PanelHead({ title, description, headingLevel = 2, className, children, ...props }: PanelHeadProps) {
    const Heading = headingLevel === 2 ? "h2" : "h3";
    return (
        <div className={cx(styles.head, className)} {...props}>
            {title && (
                <div>
                    <Heading>{title}</Heading>
                    {description && <p className={styles.description}>{description}</p>}
                </div>
            )}
            {children}
        </div>
    );
}

export function PanelBody({ className, ...props }: ComponentProps<"div">) {
    return <div className={cx(styles.body, className)} {...props} />;
}

export function PanelFoot({ className, ...props }: ComponentProps<"div">) {
    return <div className={cx(styles.foot, className)} {...props} />;
}
