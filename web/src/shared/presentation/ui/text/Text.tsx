import { Slot } from "radix-ui";
import type { ComponentProps } from "react";
import { cx } from "../cx";
import styles from "./Text.module.css";

export type TextTone = "default" | "ink" | "muted" | "faint";
export type TextSize = "md" | "sm" | "xs";

const toneClass: Record<TextTone, string | undefined> = {
    default: undefined,
    ink: styles.ink,
    muted: styles.muted,
    faint: styles.faint,
};

const sizeClass: Record<TextSize, string | undefined> = {
    md: undefined,
    sm: styles.sm,
    xs: styles.xs,
};

export type TextProps = ComponentProps<"span"> & {
    asChild?: boolean;
    tone?: TextTone;
    size?: TextSize;
    mono?: boolean;
    numeric?: boolean;
};

export function Text({
    asChild = false,
    tone = "default",
    size = "md",
    mono = false,
    numeric = false,
    className,
    ...props
}: TextProps) {
    const Element = asChild ? Slot.Root : "span";
    return (
        <Element
            className={cx(toneClass[tone], sizeClass[size], mono && styles.mono, numeric && styles.numeric, className)}
            {...props}
        />
    );
}

export function Eyebrow({ className, ...props }: ComponentProps<"div">) {
    return <div className={cx(styles.eyebrow, className)} {...props} />;
}
