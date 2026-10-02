import type { ComponentProps, ReactNode } from "react";
import { cx } from "../cx";
import styles from "./List.module.css";

export function List({ className, ...props }: ComponentProps<"ul">) {
    return <ul className={cx(styles.list, className)} {...props} />;
}

export function ListItem({ className, ...props }: ComponentProps<"li">) {
    return <li className={cx(styles.item, className)} {...props} />;
}

export function KeyValue({ className, ...props }: ComponentProps<"dl">) {
    return <dl className={cx(styles.kv, className)} {...props} />;
}

export interface KeyValueRowProps {
    term: ReactNode;
    children: ReactNode;
}

export function KeyValueRow({ term, children }: KeyValueRowProps) {
    return (
        <>
            <dt className={styles.term}>{term}</dt>
            <dd className={styles.value}>{children}</dd>
        </>
    );
}
