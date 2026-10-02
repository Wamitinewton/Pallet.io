import type { ComponentProps } from "react";
import { cx } from "../cx";
import styles from "./Badge.module.css";

export type BadgeTone = "neutral" | "blue" | "green" | "amber" | "red";

const toneClass: Record<BadgeTone, string | undefined> = {
    neutral: undefined,
    blue: styles.blue,
    green: styles.green,
    amber: styles.amber,
    red: styles.red,
};

export type BadgeProps = ComponentProps<"span"> & {
    tone?: BadgeTone;
    outline?: boolean;
    dot?: boolean;
};

export function Badge({ tone = "neutral", outline = false, dot = false, className, children, ...props }: BadgeProps) {
    return (
        <span className={cx(styles.badge, toneClass[tone], outline && styles.outline, className)} {...props}>
            {dot && <StatusDot />}
            {children}
        </span>
    );
}

export function StatusDot({ className, ...props }: Omit<ComponentProps<"span">, "children">) {
    return <span aria-hidden="true" className={cx(styles.dot, className)} {...props} />;
}

export type RoleBadgeTone = "default" | "owner";

export type RoleBadgeProps = ComponentProps<"span"> & {
    tone?: RoleBadgeTone;
};

export function RoleBadge({ tone = "default", className, ...props }: RoleBadgeProps) {
    return <span className={cx(styles.role, tone === "owner" && styles.roleOwner, className)} {...props} />;
}
