import type { ComponentProps } from "react";
import { cx } from "../cx";
import styles from "./Table.module.css";

export function TableWrap({ className, ...props }: ComponentProps<"div">) {
    return <div className={cx(styles.wrap, className)} {...props} />;
}

export function Table({ className, ...props }: ComponentProps<"table">) {
    return <table className={cx(styles.table, className)} {...props} />;
}

interface CellOptions {
    numeric?: boolean | undefined;
    actions?: boolean | undefined;
    hideOnMobile?: boolean | undefined;
}

function cellClass(base: string | undefined, { numeric, actions, hideOnMobile }: CellOptions, className?: string) {
    return cx(base, numeric && styles.numeric, actions && styles.actions, hideOnMobile && styles.hideSm, className);
}

export type ThProps = ComponentProps<"th"> & CellOptions;

export function Th({ numeric, actions, hideOnMobile, className, scope = "col", ...props }: ThProps) {
    return (
        <th scope={scope} className={cellClass(styles.th, { numeric, actions, hideOnMobile }, className)} {...props} />
    );
}

export type TdProps = ComponentProps<"td"> & CellOptions;

export function Td({ numeric, actions, hideOnMobile, className, ...props }: TdProps) {
    return <td className={cellClass(styles.td, { numeric, actions, hideOnMobile }, className)} {...props} />;
}
