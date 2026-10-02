"use client";

import { RadioGroup } from "radix-ui";
import type { ComponentProps, ReactNode } from "react";
import { cx } from "../cx";
import { useFieldControl, useFieldLabelId } from "../field/FieldContext";
import styles from "./ChoiceGroup.module.css";

export type ChoiceGroupProps = ComponentProps<typeof RadioGroup.Root>;

export function ChoiceGroup({ className, ...props }: ChoiceGroupProps) {
    const labelId = useFieldLabelId();
    const controlProps = useFieldControl(props);
    return <RadioGroup.Root aria-labelledby={labelId} className={cx(styles.grid, className)} {...controlProps} />;
}

export type ChoiceProps = Omit<ComponentProps<typeof RadioGroup.Item>, "children" | "title"> & {
    title: ReactNode;
    description?: ReactNode;
};

export function Choice({ title, description, className, ...props }: ChoiceProps) {
    return (
        <RadioGroup.Item className={cx(styles.choice, className)} {...props}>
            <span className={styles.title}>{title}</span>
            {description && <span className={styles.description}>{description}</span>}
        </RadioGroup.Item>
    );
}
