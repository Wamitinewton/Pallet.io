"use client";

import { useId, type ComponentProps, type ReactNode } from "react";
import { cx } from "../cx";
import styles from "./Field.module.css";
import { FieldContext, type FieldContextValue } from "./FieldContext";

export function Label({ className, htmlFor, children, ...props }: ComponentProps<"label">) {
    return (
        <label htmlFor={htmlFor} className={cx(styles.label, className)} {...props}>
            {children}
        </label>
    );
}

export function Hint({ className, ...props }: ComponentProps<"span">) {
    return <span className={cx(styles.hint, className)} {...props} />;
}

export function ErrorText({ className, ...props }: ComponentProps<"span">) {
    return <span className={cx(styles.error, className)} {...props} />;
}

export function SuccessText({ className, ...props }: ComponentProps<"span">) {
    return <span className={cx(styles.success, className)} {...props} />;
}

export function FieldGrid({ className, ...props }: ComponentProps<"div">) {
    return <div className={cx(styles.grid, className)} {...props} />;
}

export type FieldProps = Omit<ComponentProps<"div">, "id"> & {
    label: ReactNode;
    labelAside?: ReactNode;
    hint?: ReactNode;
    error?: ReactNode;
    success?: ReactNode;
    id?: string;
};

export function Field({ label, labelAside, hint, error, success, id, className, children, ...props }: FieldProps) {
    const generated = useId();
    const controlId = id ?? `${generated}-control`;
    const labelId = `${controlId}-label`;
    const hintId = hint ? `${controlId}-hint` : undefined;
    const successId = success && !error ? `${controlId}-success` : undefined;
    const errorId = error ? `${controlId}-error` : undefined;
    const context: FieldContextValue = {
        controlId,
        labelId,
        describedBy: [hintId, successId, errorId].filter(Boolean).join(" ") || undefined,
        invalid: Boolean(error),
    };
    const labelElement = (
        <Label id={labelId} htmlFor={controlId}>
            {label}
        </Label>
    );

    return (
        <FieldContext value={context}>
            <div className={cx(styles.field, className)} {...props}>
                {labelAside ? (
                    <div className={styles.labelRow}>
                        {labelElement}
                        {labelAside}
                    </div>
                ) : (
                    labelElement
                )}
                {children}
                {hint && <Hint id={hintId}>{hint}</Hint>}
                {successId && <SuccessText id={successId}>{success}</SuccessText>}
                {error && <ErrorText id={errorId}>{error}</ErrorText>}
            </div>
        </FieldContext>
    );
}
