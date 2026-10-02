"use client";

import { Tabs as TabsPrimitive } from "radix-ui";
import type { ComponentProps } from "react";
import { cx } from "../cx";
import styles from "./Tabs.module.css";

export const Tabs = TabsPrimitive.Root;

export type TabsListProps = ComponentProps<typeof TabsPrimitive.List> & {
    "aria-label": string;
};

export function TabsList({ className, ...props }: TabsListProps) {
    return <TabsPrimitive.List className={cx(styles.list, className)} {...props} />;
}

export type TabsTriggerProps = ComponentProps<typeof TabsPrimitive.Trigger> & {
    count?: number;
};

export function TabsTrigger({ count, className, children, ...props }: TabsTriggerProps) {
    return (
        <TabsPrimitive.Trigger className={cx(styles.trigger, className)} {...props}>
            {children}
            {count !== undefined && <span className={styles.count}>{count}</span>}
        </TabsPrimitive.Trigger>
    );
}

export const TabsContent = TabsPrimitive.Content;
