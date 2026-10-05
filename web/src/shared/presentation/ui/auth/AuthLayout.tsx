import { Slot } from "radix-ui";
import type { ComponentProps, ReactNode } from "react";
import { cx } from "../cx";
import { Icon, type IconName } from "../icons/Icon";
import styles from "./AuthLayout.module.css";

export function AuthLayout({ className, ...props }: ComponentProps<"div">) {
    return <div className={cx(styles.auth, className)} {...props} />;
}

export function AuthMain({ className, ...props }: ComponentProps<"main">) {
    return <main className={cx(styles.main, className)} {...props} />;
}

export function AuthSolo({ className, ...props }: ComponentProps<"main">) {
    return <main className={cx(styles.solo, className)} {...props} />;
}

export function AuthForm({ className, ...props }: ComponentProps<"form">) {
    return <form className={cx(styles.form, className)} {...props} />;
}

export function AuthColumn({ className, ...props }: ComponentProps<"div">) {
    return <div className={cx(styles.form, className)} {...props} />;
}

export type AuthHeaderProps = Omit<ComponentProps<"div">, "title"> & {
    title: ReactNode;
    lede?: ReactNode;
};

export function AuthHeader({ title, lede, ...props }: AuthHeaderProps) {
    return (
        <div {...props}>
            <h1>{title}</h1>
            {lede && <p className={styles.lede}>{lede}</p>}
        </div>
    );
}

export function AuthFoot({ className, ...props }: ComponentProps<"footer">) {
    return <footer className={cx(styles.foot, className)} {...props} />;
}

export function AuthSide({ className, ...props }: ComponentProps<"aside">) {
    return <aside className={cx(styles.side, className)} {...props} />;
}

export type AuthCardProps = ComponentProps<"div"> & {
    asChild?: boolean;
};

export function AuthCard({ asChild = false, className, ...props }: AuthCardProps) {
    const Component = asChild ? Slot.Root : "div";
    return <Component className={cx(styles.card, className)} {...props} />;
}

export type AuthIconTone = "blue" | "amber";

export type AuthIconProps = Omit<ComponentProps<"span">, "children"> & {
    name: IconName;
    tone?: AuthIconTone;
};

export function AuthIcon({ name, tone = "blue", className, ...props }: AuthIconProps) {
    return (
        <span
            className={cx(styles.icon, tone === "amber" && styles.iconAmber, className)}
            aria-hidden="true"
            {...props}
        >
            <Icon name={name} />
        </span>
    );
}

export function Divider({ className, children, ...props }: ComponentProps<"div">) {
    return (
        <div className={cx(styles.divider, className)} {...props}>
            {children}
        </div>
    );
}
