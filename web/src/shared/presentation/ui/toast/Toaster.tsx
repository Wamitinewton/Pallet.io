"use client";

import { Toast } from "radix-ui";
import { createContext, use, useMemo, useState, type ReactNode } from "react";
import { cx } from "../cx";
import { Icon, type IconName } from "../icons/Icon";
import styles from "./Toaster.module.css";

export type ToastKind = "success" | "error" | "info";

interface ToastMessage {
    id: string;
    kind: ToastKind;
    message: string;
}

export type ToastApi = Record<ToastKind, (message: string) => void>;

const ToastContext = createContext<ToastApi | null>(null);

const kindStyle: Record<ToastKind, { className: string | undefined; icon: IconName }> = {
    success: { className: styles.success, icon: "check-circle" },
    error: { className: styles.error, icon: "alert" },
    info: { className: styles.info, icon: "info" },
};

export interface ToasterProps {
    children: ReactNode;
    duration?: number;
}

export function Toaster({ children, duration = 3200 }: ToasterProps) {
    const [toasts, setToasts] = useState<ToastMessage[]>([]);

    const api = useMemo<ToastApi>(() => {
        const show = (kind: ToastKind) => (message: string) => {
            const id = crypto.randomUUID();
            setToasts((current) => [...current, { id, kind, message }]);
        };
        return { success: show("success"), error: show("error"), info: show("info") };
    }, []);

    const dismiss = (id: string) => {
        setToasts((current) => current.filter((toast) => toast.id !== id));
    };

    return (
        <ToastContext value={api}>
            <Toast.Provider duration={duration} swipeDirection="right" label="Notification">
                {children}
                {toasts.map(({ id, kind, message }) => (
                    <Toast.Root
                        key={id}
                        type="background"
                        className={cx(styles.toast, kindStyle[kind].className)}
                        onOpenChange={(open) => {
                            if (!open) dismiss(id);
                        }}
                    >
                        <Icon name={kindStyle[kind].icon} className={styles.icon} />
                        <Toast.Description>{message}</Toast.Description>
                    </Toast.Root>
                ))}
                <Toast.Viewport className={styles.viewport} />
            </Toast.Provider>
        </ToastContext>
    );
}

export function useToast(): ToastApi {
    const api = use(ToastContext);
    if (!api) throw new Error("useToast must be used inside <Toaster>");
    return api;
}
