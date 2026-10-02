"use client";

import { Dialog as DialogPrimitive } from "radix-ui";
import type { ComponentProps, ReactNode, SubmitEvent } from "react";
import { Button } from "../button/Button";
import { cx } from "../cx";
import { Icon } from "../icons/Icon";
import styles from "./Dialog.module.css";

export interface DialogProps {
    title: ReactNode;
    description: ReactNode;
    children: ReactNode;
    open?: boolean;
    defaultOpen?: boolean;
    onOpenChange?: (open: boolean) => void;
    trigger?: ReactNode;
    wide?: boolean;
    onSubmit?: (event: SubmitEvent<HTMLFormElement>) => void;
    className?: string;
}

export function Dialog({
    title,
    description,
    children,
    open,
    defaultOpen,
    onOpenChange,
    trigger,
    wide = false,
    onSubmit,
    className,
}: DialogProps) {
    const content = (
        <>
            <div className={styles.head}>
                <div>
                    <DialogPrimitive.Title>{title}</DialogPrimitive.Title>
                    <DialogPrimitive.Description className={styles.description}>
                        {description}
                    </DialogPrimitive.Description>
                </div>
                <DialogPrimitive.Close asChild>
                    <Button variant="ghost" size="sm" iconOnly aria-label="Close">
                        <Icon name="x" />
                    </Button>
                </DialogPrimitive.Close>
            </div>
            {children}
        </>
    );

    return (
        <DialogPrimitive.Root
            {...(open !== undefined && { open })}
            {...(defaultOpen !== undefined && { defaultOpen })}
            {...(onOpenChange && { onOpenChange })}
        >
            {trigger && <DialogPrimitive.Trigger asChild>{trigger}</DialogPrimitive.Trigger>}
            <DialogPrimitive.Portal>
                <DialogPrimitive.Overlay className={styles.backdrop}>
                    <DialogPrimitive.Content className={cx(styles.dialog, wide && styles.wide, className)}>
                        {onSubmit ? (
                            <form
                                noValidate
                                onSubmit={(event) => {
                                    event.preventDefault();
                                    onSubmit(event);
                                }}
                            >
                                {content}
                            </form>
                        ) : (
                            content
                        )}
                    </DialogPrimitive.Content>
                </DialogPrimitive.Overlay>
            </DialogPrimitive.Portal>
        </DialogPrimitive.Root>
    );
}

export function DialogBody({ className, ...props }: ComponentProps<"div">) {
    return <div className={cx(styles.body, className)} {...props} />;
}

export function DialogFooter({ className, ...props }: ComponentProps<"div">) {
    return <div className={cx(styles.foot, className)} {...props} />;
}

export function DialogClose(props: Omit<ComponentProps<typeof DialogPrimitive.Close>, "asChild">) {
    return <DialogPrimitive.Close asChild {...props} />;
}
