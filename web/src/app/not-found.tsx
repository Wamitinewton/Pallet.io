import { Brand, Button, EmptyState } from "@/shared/presentation/ui";
import type { Metadata } from "next";
import Link from "next/link";
import styles from "./not-found.module.css";

export const metadata: Metadata = {
    title: "Page not found",
};

export default function NotFound() {
    return (
        <main className={styles.page}>
            <Brand asChild>
                <Link href="/" />
            </Brand>
            <EmptyState
                icon="search"
                title="We couldn't find that page"
                description="The address may be mistyped, or the page may have moved."
                actions={
                    <Button asChild variant="primary">
                        <Link href="/orgs">Go to your organizations</Link>
                    </Button>
                }
            />
        </main>
    );
}
