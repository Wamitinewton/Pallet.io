import type { ComponentProps } from "react";
import { cx } from "../cx";
import styles from "./Stack.module.css";

export type StackProps = ComponentProps<"div"> & {
    gap?: "sm" | "md";
};

export function Stack({ gap = "md", className, ...props }: StackProps) {
    return <div className={cx(styles.stack, gap === "sm" && styles.stackSm, className)} {...props} />;
}

export type RowProps = ComponentProps<"div"> & {
    justify?: "start" | "between";
};

export function Row({ justify = "start", className, ...props }: RowProps) {
    return <div className={cx(styles.row, justify === "between" && styles.between, className)} {...props} />;
}
