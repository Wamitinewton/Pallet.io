import { Slot } from "radix-ui";
import type { ComponentProps, CSSProperties, ReactNode } from "react";
import { cx } from "../cx";
import styles from "./Stat.module.css";

export type StatsProps = ComponentProps<"div"> & {
    columns?: number;
};

export function Stats({ columns = 3, className, style, ...props }: StatsProps) {
    return (
        <div
            className={cx(styles.stats, className)}
            style={{ "--stat-columns": columns, ...style } as CSSProperties}
            {...props}
        />
    );
}

export type StatProps = Omit<ComponentProps<"div">, "children"> & {
    value: ReactNode;
    label: ReactNode;
    asChild?: boolean;
    children?: ReactNode;
};

export function Stat({ value, label, asChild = false, className, children, ...props }: StatProps) {
    const content = (
        <>
            <span className={styles.value}>{value}</span>
            <span className={styles.label}>{label}</span>
        </>
    );
    if (asChild) {
        return (
            <Slot.Root className={cx(styles.stat, className)} {...props}>
                <Slot.Slottable>{children}</Slot.Slottable>
                {content}
            </Slot.Root>
        );
    }
    return (
        <div className={cx(styles.stat, className)} {...props}>
            {content}
        </div>
    );
}
