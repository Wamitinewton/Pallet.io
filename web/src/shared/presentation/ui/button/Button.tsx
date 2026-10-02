"use client";

import { Slot } from "radix-ui";
import type { ComponentProps, MouseEvent } from "react";
import { cx } from "../cx";
import { Spinner } from "../spinner/Spinner";
import styles from "./Button.module.css";

export type ButtonVariant = "primary" | "secondary" | "ghost" | "danger" | "danger-outline";
export type ButtonSize = "sm" | "md" | "lg";

export type ButtonProps = ComponentProps<"button"> & {
    variant?: ButtonVariant;
    size?: ButtonSize;
    block?: boolean;
    iconOnly?: boolean;
    loading?: boolean;
    asChild?: boolean;
};

const variantClass: Record<ButtonVariant, string | undefined> = {
    primary: styles.primary,
    secondary: styles.secondary,
    ghost: styles.ghost,
    danger: styles.danger,
    "danger-outline": styles.dangerOutline,
};

const sizeClass: Record<ButtonSize, string | undefined> = {
    sm: styles.sm,
    md: undefined,
    lg: styles.lg,
};

interface StyleOptions {
    variant?: ButtonVariant | undefined;
    size?: ButtonSize | undefined;
    block?: boolean | undefined;
    iconOnly?: boolean | undefined;
    className?: string | undefined;
}

function buttonClassName({
    variant = "secondary",
    size = "md",
    block = false,
    iconOnly = false,
    className,
}: StyleOptions): string {
    return cx(
        styles.button,
        variantClass[variant],
        sizeClass[size],
        block && styles.block,
        iconOnly && styles.iconOnly,
        className,
    );
}

export function Button({
    variant,
    size,
    block,
    iconOnly,
    loading = false,
    asChild = false,
    className,
    type,
    onClick,
    children,
    ...props
}: ButtonProps) {
    const classes = buttonClassName({ variant, size, block, iconOnly, className });
    const busy = loading ? { "aria-busy": true, "aria-disabled": true } : {};
    const handleClick = (event: MouseEvent<HTMLButtonElement>) => {
        if (loading) {
            event.preventDefault();
            return;
        }
        onClick?.(event);
    };

    if (asChild) {
        return (
            <Slot.Root className={classes} onClick={handleClick} {...busy} {...props}>
                {children}
            </Slot.Root>
        );
    }

    return (
        <button type={type ?? "button"} className={classes} onClick={handleClick} {...busy} {...props}>
            {loading ? (
                <>
                    <span className={styles.content}>{children}</span>
                    <Spinner className={styles.spinner} />
                </>
            ) : (
                children
            )}
        </button>
    );
}
