import type { ComponentProps } from "react";
import { cx } from "../cx";
import styles from "./Sha.module.css";

export type ShaProps = Omit<ComponentProps<"code">, "children"> & {
    value: string;
    length?: number;
};

export function Sha({ value, length = 7, className, ...props }: ShaProps) {
    return (
        <code className={cx(styles.sha, className)} title={value} {...props}>
            {value.slice(0, length)}
        </code>
    );
}
