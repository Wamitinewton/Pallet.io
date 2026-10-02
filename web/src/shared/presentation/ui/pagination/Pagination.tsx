"use client";

import type { ComponentProps } from "react";
import { Button } from "../button/Button";
import { cx } from "../cx";
import { Icon } from "../icons/Icon";
import styles from "./Pagination.module.css";

export type PaginationProps = Omit<ComponentProps<"nav">, "onChange"> & {
    page: number;
    totalPages: number;
    onPageChange: (page: number) => void;
    disabled?: boolean;
};

export function Pagination({
    page,
    totalPages,
    onPageChange,
    disabled = false,
    className,
    "aria-label": ariaLabel = "Pagination",
    ...props
}: PaginationProps) {
    if (totalPages <= 1) return null;
    const current = Math.min(Math.max(page, 1), totalPages);

    return (
        <nav aria-label={ariaLabel} className={cx(styles.pagination, className)} {...props}>
            <span className={styles.status} aria-live="polite">
                Page {current} of {totalPages}
            </span>
            <div className={styles.controls}>
                <Button
                    size="sm"
                    disabled={disabled || current <= 1}
                    onClick={() => {
                        onPageChange(current - 1);
                    }}
                >
                    <Icon name="back" />
                    Previous
                </Button>
                <Button
                    size="sm"
                    disabled={disabled || current >= totalPages}
                    onClick={() => {
                        onPageChange(current + 1);
                    }}
                >
                    Next
                    <Icon name="arrow" />
                </Button>
            </div>
        </nav>
    );
}
