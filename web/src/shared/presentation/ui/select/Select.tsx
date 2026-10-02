"use client";

import type { ComponentProps } from "react";
import { cx } from "../cx";
import { useFieldControl } from "../field/FieldContext";
import { Icon } from "../icons/Icon";
import styles from "./Select.module.css";

export type SelectProps = ComponentProps<"select">;

export function Select({ className, ...props }: SelectProps) {
    const controlProps = useFieldControl(props);
    return (
        <span className={cx(styles.wrap, className)}>
            <select className={styles.select} {...controlProps} />
            <Icon name="chevron-down" className={styles.chevron} />
        </span>
    );
}
