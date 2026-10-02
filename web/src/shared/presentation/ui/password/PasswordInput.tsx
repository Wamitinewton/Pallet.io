"use client";

import { useState, type ComponentProps, type CSSProperties } from "react";
import { Button } from "../button/Button";
import { cx } from "../cx";
import { Icon } from "../icons/Icon";
import { Input, type InputProps } from "../input/Input";
import styles from "./PasswordInput.module.css";

export type PasswordInputProps = Omit<InputProps, "type" | "mono">;

export function PasswordInput({ className, ...props }: PasswordInputProps) {
    const [revealed, setRevealed] = useState(false);
    return (
        <span className={cx(styles.wrap, className)}>
            <Input type={revealed ? "text" : "password"} spellCheck={false} {...props} />
            <Button
                variant="ghost"
                size="sm"
                iconOnly
                className={styles.toggle}
                aria-label="Show password"
                aria-pressed={revealed}
                disabled={props.disabled}
                onClick={() => {
                    setRevealed((current) => !current);
                }}
            >
                <Icon name={revealed ? "eye-off" : "eye"} />
            </Button>
        </span>
    );
}

export type StrengthMeterProps = Omit<ComponentProps<"div">, "children"> & {
    score: number;
    max?: number;
    label?: string;
};

export function StrengthMeter({
    score,
    max = 4,
    label = "Password strength",
    className,
    style,
    ...props
}: StrengthMeterProps) {
    const value = Math.max(0, Math.min(max, Math.round(score)));
    return (
        <div
            role="meter"
            aria-label={label}
            aria-valuemin={0}
            aria-valuemax={max}
            aria-valuenow={value}
            className={cx(styles.strength, className)}
            style={{ "--strength-max": max, ...style } as CSSProperties}
            {...props}
        >
            {Array.from({ length: max }, (_, index) => (
                <span key={index} className={cx(styles.bar, index < value && styles.on)} />
            ))}
        </div>
    );
}
