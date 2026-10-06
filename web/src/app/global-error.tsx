"use client";

import { fontVariables } from "./fonts";
import styles from "./global-error.module.css";
import "./globals.css";

export interface GlobalErrorProps {
    readonly error: Error & { digest?: string };
    readonly retry: () => void;
}

/** Replaces the root layout, so it renders without any provider, theme script or shared component. */
export default function GlobalError({ error, retry }: GlobalErrorProps) {
    return (
        <html lang="en" className={fontVariables}>
            <body>
                <main className={styles.page}>
                    <h1>Pallet ran into a problem</h1>
                    <p className={styles.text}>Something went wrong on our side. Try again in a moment.</p>
                    {error.digest !== undefined && <p className={styles.reference}>Reference {error.digest}</p>}
                    <button type="button" className={styles.button} onClick={retry}>
                        Try again
                    </button>
                </main>
            </body>
        </html>
    );
}
