import type { ComponentProps, ReactNode } from "react";
import { cx } from "../cx";
import styles from "./PreviewWindow.module.css";

export type PreviewWindowProps = Omit<ComponentProps<"div">, "title"> & {
    title?: ReactNode;
};

export function PreviewWindow({ title, className, children, ...props }: PreviewWindowProps) {
    return (
        <div className={cx(styles.window, className)} {...props}>
            {title !== undefined && (
                <div className={styles.bar}>
                    <span className={styles.light} aria-hidden="true" />
                    <span className={styles.light} aria-hidden="true" />
                    <span className={styles.light} aria-hidden="true" />
                    <span className={styles.title}>{title}</span>
                </div>
            )}
            {children}
        </div>
    );
}
