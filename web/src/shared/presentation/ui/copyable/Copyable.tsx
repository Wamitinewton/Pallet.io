"use client";

import type { ComponentProps } from "react";
import { cx } from "../cx";
import { Icon } from "../icons/Icon";
import { useToast } from "../toast/Toaster";
import styles from "./Copyable.module.css";

export type CopyableProps = Omit<ComponentProps<"span">, "children"> & {
    value: string;
    label?: string;
};

export function Copyable({ value, label, className, ...props }: CopyableProps) {
    const toast = useToast();
    const copy = async () => {
        try {
            await navigator.clipboard.writeText(value);
            toast.success("Copied to clipboard");
        } catch {
            toast.error("Couldn't copy. Select the text and copy it instead.");
        }
    };

    return (
        <span className={cx(styles.copyable, className)} {...props}>
            <span className={styles.value}>{value}</span>
            <button
                type="button"
                className={styles.button}
                aria-label={label ? `Copy ${label}` : "Copy"}
                onClick={() => void copy()}
            >
                <Icon name="copy" />
            </button>
        </span>
    );
}
