"use client";

import type { ComponentProps, ReactNode } from "react";
import { cx } from "../cx";
import { Icon } from "../icons/Icon";
import { useToast } from "../toast/Toaster";
import styles from "./Copyable.module.css";

export type CopyableProps = Omit<ComponentProps<"span">, "children"> & {
    value: string;
    label?: string;
    /** Shown in place of the value, such as a short SHA for a full one. */
    display?: ReactNode;
};

export function Copyable({ value, label, display, className, ...props }: CopyableProps) {
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
            <span className={styles.value}>{display ?? value}</span>
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
