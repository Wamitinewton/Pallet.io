import type { ComponentProps, CSSProperties } from "react";
import { cx } from "../cx";
import styles from "./Spinner.module.css";

export type SpinnerProps = Omit<ComponentProps<"span">, "children"> & {
    size?: number;
    label?: string;
};

export function Spinner({ size = 16, label, className, style, ...props }: SpinnerProps) {
    const a11y = label ? { role: "status", "aria-label": label } : { "aria-hidden": true };
    return (
        <span
            className={cx(styles.spinner, className)}
            style={{ "--spinner-size": `${String(size)}px`, ...style } as CSSProperties}
            {...a11y}
            {...props}
        />
    );
}
