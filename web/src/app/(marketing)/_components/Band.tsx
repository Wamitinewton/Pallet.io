import { cx } from "@/shared/presentation/ui";
import type { ComponentProps, ReactNode } from "react";
import styles from "./Band.module.css";
import { Container } from "./Container";

export type BandProps = Omit<ComponentProps<"section">, "id"> & {
    id: string;
    tint?: boolean;
    layout?: "stacked" | "split";
};

/** A full-width landing section, labelled by its own heading so it is a named region. */
export function Band({ id, tint = false, layout = "stacked", className, children, ...props }: BandProps) {
    return (
        <section
            id={id}
            aria-labelledby={`${id}-title`}
            className={cx(styles.band, tint && styles.tint, className)}
            {...props}
        >
            <Container className={layout === "split" ? styles.split : undefined}>{children}</Container>
        </section>
    );
}

export interface BandHeadProps {
    readonly id: string;
    readonly eyebrow: string;
    readonly title: ReactNode;
    readonly children?: ReactNode;
}

export function BandHead({ id, eyebrow, title, children }: BandHeadProps) {
    return (
        <header className={styles.head}>
            <p className={styles.eyebrow}>{eyebrow}</p>
            <h2 id={`${id}-title`} className={styles.title}>
                {title}
            </h2>
            {children}
        </header>
    );
}

export function Reveal({ className, ...props }: ComponentProps<"div">) {
    return <div className={cx(styles.reveal, className)} {...props} />;
}
