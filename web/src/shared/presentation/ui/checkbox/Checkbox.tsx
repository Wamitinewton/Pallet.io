"use client";

import type { ComponentProps, ReactNode } from "react";
import { cx } from "../cx";
import styles from "./Checkbox.module.css";

export type CheckboxProps = Omit<ComponentProps<"input">, "type"> & {
    label: ReactNode;
    description?: ReactNode;
};

export function Checkbox({ label, description, className, ...props }: CheckboxProps) {
    return (
        <label className={cx(styles.checkbox, className)}>
            <input type="checkbox" className={styles.input} {...props} />
            <span className={styles.text}>
                <span>{label}</span>
                {description && <span className={styles.description}>{description}</span>}
            </span>
        </label>
    );
}
