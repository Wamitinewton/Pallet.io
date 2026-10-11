import { authPaths } from "@/modules/session";
import { Brand, Icon } from "@/shared/presentation/ui";
import Link from "next/link";
import { Container } from "../_components/Container";
import { REPOSITORY_URL, repositoryFileUrl, SITE_NAV } from "../_components/landing-links";
import styles from "./SiteFooter.module.css";

export function SiteFooter() {
    return (
        <footer className={styles.footer}>
            <Container className={styles.inner}>
                <div className={styles.about}>
                    <Brand asChild>
                        <Link href="/" aria-label="Pallet home" />
                    </Brand>
                    <p>
                        A small platform-as-a-service, built in public. Open source under the{" "}
                        <a href={repositoryFileUrl("LICENSE")}>Apache 2.0 license</a>.
                    </p>
                </div>
                <nav className={styles.nav} aria-label="Footer">
                    <div className={styles.column}>
                        <p className={styles.heading}>Pallet</p>
                        <ul>
                            {SITE_NAV.map(({ href, label }) => (
                                <li key={href}>
                                    <a href={href}>{label}</a>
                                </li>
                            ))}
                        </ul>
                    </div>
                    <div className={styles.column}>
                        <p className={styles.heading}>Account</p>
                        <ul>
                            <li>
                                <Link href={authPaths.signUp}>Create an organization</Link>
                            </li>
                            <li>
                                <Link href={authPaths.signIn()}>Sign in</Link>
                            </li>
                            <li>
                                <Link href={authPaths.forgotPassword()}>Reset your password</Link>
                            </li>
                        </ul>
                    </div>
                    <div className={styles.column}>
                        <p className={styles.heading}>Source</p>
                        <ul>
                            <li>
                                <a href={REPOSITORY_URL} className={styles.external}>
                                    GitHub <Icon name="external" size={13} />
                                </a>
                            </li>
                            <li>
                                <a href={repositoryFileUrl("PROJECT.md")} className={styles.external}>
                                    Design brief <Icon name="external" size={13} />
                                </a>
                            </li>
                        </ul>
                    </div>
                </nav>
            </Container>
        </footer>
    );
}
