import { cx } from "@/shared/presentation/ui";
import type { ComponentProps } from "react";
import styles from "./Container.module.css";

export function Container({ className, ...props }: ComponentProps<"div">) {
    return <div className={cx(styles.container, className)} {...props} />;
}
