import { Slot } from "radix-ui";
import type { ComponentProps } from "react";
import { cx } from "../cx";
import styles from "./Brand.module.css";
import { BRAND_MARK } from "./brand-mark";

export function BrandMark({ className, ...props }: Omit<ComponentProps<"svg">, "children">) {
    return (
        <svg
            viewBox={BRAND_MARK.viewBox}
            aria-hidden="true"
            focusable="false"
            className={cx(styles.mark, className)}
            {...props}
        >
            <g fill="none" strokeLinecap="round" strokeLinejoin="round">
                <path className={styles.green} d={BRAND_MARK.upperChevron} strokeWidth={36} />
                <path className={styles.blue} d={BRAND_MARK.lowerChevron} strokeWidth={38} />
            </g>
            <g className={styles.solid} strokeWidth={14} strokeLinejoin="round">
                {BRAND_MARK.blocks.map((d) => (
                    <path key={d} d={d} />
                ))}
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
