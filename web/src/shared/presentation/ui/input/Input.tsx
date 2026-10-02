"use client";

import type { ComponentProps, ReactNode } from "react";
import { cx } from "../cx";
import { useFieldControl } from "../field/FieldContext";
import styles from "./Input.module.css";

export type InputProps = ComponentProps<"input"> & {
    mono?: boolean;
};

export function Input({ mono = false, className, ...props }: InputProps) {
    const controlProps = useFieldControl(props);
    return <input className={cx(styles.control, mono && styles.mono, className)} {...controlProps} />;
}

export type TextareaProps = ComponentProps<"textarea"> & {
    mono?: boolean;
};

export function Textarea({ mono = false, className, ...props }: TextareaProps) {
    const controlProps = useFieldControl(props);
    return (
        <textarea className={cx(styles.control, styles.textarea, mono && styles.mono, className)} {...controlProps} />
    );
}

export type InputGroupProps = ComponentProps<"div"> & {
    addon: ReactNode;
};

export function InputGroup({ addon, className, children, ...props }: InputGroupProps) {
    return (
        <div className={cx(styles.group, className)} {...props}>
            <span className={styles.addon} aria-hidden="true">
                {addon}
            </span>
            {children}
        </div>
    );
}
