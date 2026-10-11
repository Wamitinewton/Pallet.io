import { authPaths } from "@/modules/session";
import { Badge, Button, Icon } from "@/shared/presentation/ui";
import Link from "next/link";
import { Container } from "../_components/Container";
import { SECTION_ID, sectionHref } from "../_components/landing-links";
import styles from "./Hero.module.css";
import { HeroDemo } from "./HeroDemo";

export function Hero() {
    return (
        <section className={styles.hero} aria-labelledby="hero-title">
            <div className={styles.backdrop} aria-hidden="true" />
            <Container className={styles.grid}>
                <div className={styles.copy}>
                    <p className={styles.pill}>
                        <Badge tone="blue">Early access</Badge>
                        Built in public, one service at a time
                    </p>
                    <h1 id="hero-title" className={styles.title}>
                        Push to your branch. Pallet <em>takes it from there.</em>
                    </h1>
                    <p className={styles.lede}>
                        Pallet runs your web apps from a GitHub repository. Link a repo to an app, choose the branch
                        that counts as production, and every push to it is picked up and queued for a build. Your team,
                        their roles and your GitHub access all live in one organization.
                    </p>
                    <div className={styles.cta}>
                        <Button asChild variant="primary" size="lg">
                            <Link href={authPaths.signUp}>
                                Create your organization <Icon name="arrow" />
                            </Link>
                        </Button>
                        <Button asChild variant="secondary" size="lg">
                            <a href={sectionHref(SECTION_ID.how)}>See how it works</a>
                        </Button>
                    </div>
                    <p className={styles.fine}>
                        <Icon name="check" size={14} />
                        No card needed. You only need an email address.
                    </p>
                </div>
                <HeroDemo />
            </Container>
        </section>
    );
}
