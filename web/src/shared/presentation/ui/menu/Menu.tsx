"use client";

import { DropdownMenu } from "radix-ui";
import type { ComponentProps } from "react";
import { cx } from "../cx";
import styles from "./Menu.module.css";

export const Menu = DropdownMenu.Root;

export function MenuTrigger(props: Omit<ComponentProps<typeof DropdownMenu.Trigger>, "asChild">) {
    return <DropdownMenu.Trigger asChild {...props} />;
}

export function MenuContent({
    className,
    align = "end",
    sideOffset = 6,
    ...props
}: ComponentProps<typeof DropdownMenu.Content>) {
    return (
        <DropdownMenu.Portal>
            <DropdownMenu.Content
                align={align}
                sideOffset={sideOffset}
                className={cx(styles.content, className)}
                {...props}
            />
        </DropdownMenu.Portal>
    );
}

export type MenuItemProps = ComponentProps<typeof DropdownMenu.Item> & {
    tone?: "default" | "danger";
};

export function MenuItem({ tone = "default", className, ...props }: MenuItemProps) {
    return <DropdownMenu.Item className={cx(styles.item, tone === "danger" && styles.danger, className)} {...props} />;
}

export function MenuLabel({ className, ...props }: ComponentProps<typeof DropdownMenu.Label>) {
    return <DropdownMenu.Label className={cx(styles.label, className)} {...props} />;
}

export function MenuSeparator({ className, ...props }: ComponentProps<typeof DropdownMenu.Separator>) {
    return <DropdownMenu.Separator className={cx(styles.separator, className)} {...props} />;
}
