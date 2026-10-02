import { Slot } from "radix-ui";
import type { ComponentProps } from "react";
import { cx } from "../cx";
import styles from "./Brand.module.css";

export function BrandMark({ className, ...props }: Omit<ComponentProps<"svg">, "children">) {
    return (
        <svg
            viewBox="-6 -6 362 348"
            aria-hidden="true"
            focusable="false"
            className={cx(styles.mark, className)}
            {...props}
        >
            <g fill="none" strokeLinecap="round" strokeLinejoin="round">
                <path className={styles.green} d="M40 86 L175 21 L310 86" strokeWidth={36} />
                <path className={styles.blue} d="M22 150 L175 226 L328 150" strokeWidth={38} />
            </g>
            <g className={styles.solid} strokeWidth={14} strokeLinejoin="round">
                <path d="M100 127 L175 90 L250 127 L175 164 Z" />
                <path d="M8 206 L56 229 L56 272 L8 249 Z" />
                <path d="M294 229 L342 206 L342 249 L294 272 Z" />
                <path d="M136 267 L175 286 L214 267 L214 304 L175 323 L136 304 Z" />
            </g>
        </svg>
    );
}

export type BrandProps = ComponentProps<"span"> & {
    asChild?: boolean;
};

export function Brand({ asChild = false, className, children, ...props }: BrandProps) {
    const Root = asChild ? Slot.Root : "span";
    return (
        <Root className={cx(styles.brand, className)} {...props}>
            {asChild && <Slot.Slottable>{children}</Slot.Slottable>}
            <BrandMark />
            <span className={styles.wordmark}>Pallet</span>
        </Root>
    );
}
