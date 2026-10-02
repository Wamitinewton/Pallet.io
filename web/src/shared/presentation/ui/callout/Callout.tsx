import type { ComponentProps } from "react";
import { cx } from "../cx";
import { Icon, type IconName } from "../icons/Icon";
import styles from "./Callout.module.css";

export type CalloutTone = "neutral" | "blue" | "amber" | "red" | "green";

const toneStyle: Record<CalloutTone, { className: string | undefined; icon: IconName }> = {
    neutral: { className: undefined, icon: "info" },
    blue: { className: styles.blue, icon: "info" },
    amber: { className: styles.amber, icon: "alert" },
    red: { className: styles.red, icon: "alert" },
    green: { className: styles.green, icon: "check-circle" },
};

export type CalloutProps = ComponentProps<"div"> & {
    tone?: CalloutTone;
    icon?: IconName;
};

export function Callout({ tone = "neutral", icon, className, children, ...props }: CalloutProps) {
    const style = toneStyle[tone];
    return (
        <div className={cx(styles.callout, style.className, className)} {...props}>
            <Icon name={icon ?? style.icon} className={styles.icon} />
            <div className={styles.content}>{children}</div>
        </div>
    );
}
