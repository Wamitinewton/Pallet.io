import { authPaths } from "@/modules/session";
import { BrandMark, Button, Icon } from "@/shared/presentation/ui";
import Link from "next/link";
import { Container } from "../_components/Container";
import { SessionLink } from "../_components/SessionLink";
import styles from "./ClosingCta.module.css";

export function ClosingCta() {
    return (
        <section className={styles.section} aria-labelledby="closing-title">
            <Container>
                <div className={styles.card}>
                    <BrandMark className={styles.watermark} />
                    <div className={styles.copy}>
                        <h2 id="closing-title" className={styles.title}>
                            Set up your organization
                        </h2>
                        <p className={styles.text}>It takes about a minute. Invite your team once you&apos;re in.</p>
                    </div>
                    <div className={styles.actions}>
                        <Button asChild variant="primary" size="lg">
                            <Link href={authPaths.signUp}>
                                Get started <Icon name="arrow" />
                            </Link>
                        </Button>
                        <SessionLink variant="secondary" size="lg" className={styles.secondary} />
                    </div>
                </div>
            </Container>
        </section>
    );
}
