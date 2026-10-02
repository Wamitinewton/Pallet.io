"use client";

import { ToggleGroup } from "radix-ui";
import type { ComponentProps } from "react";
import { cx } from "../cx";
import styles from "./Segmented.module.css";

export type SegmentedProps<T extends string> = Omit<
    ComponentProps<typeof ToggleGroup.Root>,
    "type" | "value" | "defaultValue" | "onValueChange" | "aria-label"
> & {
    "aria-label": string;
    value: T;
    onValueChange: (value: T) => void;
};

export function Segmented<T extends string>({ value, onValueChange, className, ...props }: SegmentedProps<T>) {
    return (
        <ToggleGroup.Root
            type="single"
            value={value}
            onValueChange={(next: string) => {
                if (next) onValueChange(next as T);
            }}
            className={cx(styles.root, className)}
            {...props}
        />
    );
}

export type SegmentedItemProps = ComponentProps<typeof ToggleGroup.Item>;

export function SegmentedItem({ className, ...props }: SegmentedItemProps) {
    return <ToggleGroup.Item className={cx(styles.item, className)} {...props} />;
}
