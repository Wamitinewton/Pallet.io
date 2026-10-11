import { authPaths } from "@/modules/session";
import { Brand, Button, ThemeToggle } from "@/shared/presentation/ui";
import Link from "next/link";
import { Container } from "../_components/Container";
import { SITE_NAV } from "../_components/landing-links";
import { SessionLink } from "../_components/SessionLink";
import styles from "./SiteHeader.module.css";

export function SiteHeader() {
    return (
        <header className={styles.header}>
            <Container className={styles.bar}>
                <Brand asChild>
                    <Link href="/" aria-label="Pallet home" />
                </Brand>
                <nav className={styles.nav} aria-label="Site">
                    {SITE_NAV.map(({ href, label }) => (
                        <a key={href} href={href}>
                            {label}
                        </a>
                    ))}
                </nav>
                <div className={styles.actions}>
                    <ThemeToggle />
                    <SessionLink variant="ghost" />
                    <Button asChild variant="primary" className={styles.signUp}>
                        <Link href={authPaths.signUp}>Get started</Link>
                    </Button>
                </div>
            </Container>
        </header>
    );
}
