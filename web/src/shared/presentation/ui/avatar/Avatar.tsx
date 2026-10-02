import type { ComponentProps, ReactNode } from "react";
import { cx } from "../cx";
import styles from "./Avatar.module.css";
import { initials } from "./initials";

export type AvatarSize = "sm" | "md" | "lg";

const sizeClass: Record<AvatarSize, string | undefined> = {
    sm: styles.sm,
    md: undefined,
    lg: styles.lg,
};

export type AvatarProps = Omit<ComponentProps<"span">, "children"> & {
    name: string;
    size?: AvatarSize;
    decorative?: boolean;
};

export function Avatar({ name, size = "md", decorative = true, className, ...props }: AvatarProps) {
    return <AvatarBase name={name} size={size} decorative={decorative} className={className} {...props} />;
}

export function OrgAvatar({ name, size = "md", decorative = true, className, ...props }: AvatarProps) {
    return (
        <AvatarBase name={name} size={size} decorative={decorative} className={cx(styles.org, className)} {...props} />
    );
}

function AvatarBase({
    name,
    size,
    decorative,
    className,
    ...props
}: Omit<AvatarProps, "size" | "decorative"> & { size: AvatarSize; decorative: boolean }) {
    const a11y = decorative ? { "aria-hidden": true } : { role: "img", "aria-label": name };
    return (
        <span className={cx(styles.avatar, sizeClass[size], className)} {...a11y} {...props}>
            {initials(name)}
        </span>
    );
}

export function AvatarStack({ className, ...props }: ComponentProps<"span">) {
    return <span className={cx(styles.stack, className)} {...props} />;
}

export type PersonProps = Omit<ComponentProps<"div">, "children"> & {
    name: ReactNode;
    meta?: ReactNode;
    avatar?: ReactNode;
};

export function Person({ name, meta, avatar, className, ...props }: PersonProps) {
    return (
        <div className={cx(styles.person, className)} {...props}>
            {avatar}
            <div className={styles.personText}>
                <div className={styles.personName}>{name}</div>
                {meta && <div className={styles.personMeta}>{meta}</div>}
            </div>
        </div>
    );
}
