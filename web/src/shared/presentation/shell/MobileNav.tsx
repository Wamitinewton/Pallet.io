"use client";

import { Dialog } from "radix-ui";
import type { ReactNode } from "react";
import { Button } from "../ui/button/Button";
import { Icon } from "../ui/icons/Icon";
import styles from "./MobileNav.module.css";

export interface MobileNavProps {
    readonly open: boolean;
    readonly onOpenChange: (open: boolean) => void;
    readonly children: ReactNode;
}

/** The sidebar as a modal drawer under 960px: focus stays inside, Escape and the backdrop close it. */
export function MobileNav({ open, onOpenChange, children }: MobileNavProps) {
    return (
        <Dialog.Root open={open} onOpenChange={onOpenChange}>
            <Dialog.Trigger asChild>
                <Button variant="ghost" iconOnly className={styles.toggle} aria-label="Open navigation">
                    <Icon name="menu" />
                </Button>
            </Dialog.Trigger>
            <Dialog.Portal>
                <Dialog.Overlay className={styles.backdrop} />
                <Dialog.Content className={styles.drawer} aria-describedby={undefined}>
                    <Dialog.Title className={styles.title}>Navigation</Dialog.Title>
                    <Dialog.Close asChild>
                        <Button
                            variant="ghost"
                            size="sm"
                            iconOnly
                            className={styles.close}
                            aria-label="Close navigation"
                        >
                            <Icon name="x" />
                        </Button>
                    </Dialog.Close>
                    {children}
                </Dialog.Content>
            </Dialog.Portal>
        </Dialog.Root>
    );
}
