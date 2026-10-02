import type { ComponentProps } from "react";
import { cx } from "../cx";
import styles from "./Skeleton.module.css";

type Length = number | string;

const toCss = (length: Length) => (typeof length === "number" ? `${String(length)}px` : length);

export type SkeletonProps = Omit<ComponentProps<"span">, "children"> & {
    width?: Length;
    height?: Length;
    circle?: boolean;
};

export function Skeleton({ width, height, circle = false, className, style, ...props }: SkeletonProps) {
    const size = {
        ...(width !== undefined && { "--skeleton-width": toCss(width) }),
        ...(height !== undefined && { "--skeleton-height": toCss(height) }),
    };
    return (
        <span
            aria-hidden="true"
            className={cx(styles.skeleton, circle && styles.circle, className)}
            style={{ ...size, ...style }}
            {...props}
        />
    );
}

export type SkeletonTextProps = Omit<ComponentProps<"span">, "children"> & {
    lines?: number;
};

export function SkeletonText({ lines = 3, className, ...props }: SkeletonTextProps) {
    return (
        <span aria-hidden="true" className={cx(styles.lines, className)} {...props}>
            {Array.from({ length: lines }, (_, index) => (
                <Skeleton key={index} />
            ))}
        </span>
    );
}
