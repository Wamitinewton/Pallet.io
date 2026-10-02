"use client";

import { Switch as SwitchPrimitive } from "radix-ui";
import type { ComponentProps } from "react";
import { cx } from "../cx";
import { useFieldControl } from "../field/FieldContext";
import styles from "./Switch.module.css";

export type SwitchProps = Omit<ComponentProps<typeof SwitchPrimitive.Root>, "children">;

export function Switch({ className, ...props }: SwitchProps) {
    const controlProps = useFieldControl(props);
    return (
        <SwitchPrimitive.Root className={cx(styles.root, className)} {...controlProps}>
            <SwitchPrimitive.Thumb className={styles.thumb} />
        </SwitchPrimitive.Root>
    );
}
