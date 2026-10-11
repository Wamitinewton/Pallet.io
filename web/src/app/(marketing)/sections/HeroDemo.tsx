import { Badge, Icon, KeyValue, KeyValueRow, PreviewWindow, Sha, VisuallyHidden } from "@/shared/presentation/ui";
import styles from "./HeroDemo.module.css";

export function HeroDemo() {
    return (
        <figure className={styles.demo}>
            <figcaption>
                <VisuallyHidden>
                    Example: a push to main on GitHub shows up on the linked Pallet app as its latest push, with a build
                    queued.
                </VisuallyHidden>
            </figcaption>

            <div className={styles.scene} aria-hidden="true">
                <div className={styles.terminal}>
                    <div className={styles.terminalBar}>
                        <span className={styles.light} />
                        <span className={styles.light} />
                        <span className={styles.light} />
                        <span className={styles.terminalTitle}>checkout-api — zsh</span>
                    </div>
                    <pre className={styles.terminalBody}>
                        <span className={styles.line}>
                            <span className={styles.cwd}>~/checkout-api</span> <span className={styles.prompt}>$</span>{" "}
                            <span className={styles.typed}>git push origin main</span>
                        </span>
                        <span className={styles.line}>Enumerating objects: 9, done.</span>
                        <span className={styles.line}>Writing objects: 100% (5/5), 1.21 KiB, done.</span>
                        <span className={styles.line}>To github.com:kilima-labs/checkout-api.git</span>
                        <span className={styles.line}>
                            {"   8d41b07..3f9c2e1  "}
                            <span className={styles.ref}>main -&gt; main</span>
                        </span>
                        <span className={styles.line}>
                            <span className={styles.cwd}>~/checkout-api</span> <span className={styles.prompt}>$</span>{" "}
                            <span className={styles.caret} />
                        </span>
                    </pre>
                </div>

                <div className={styles.flow}>
                    <span className={styles.rail} />
                    <span className={styles.event}>
                        <Icon name="github" size={14} />
                        Push received
                        <Icon name="chevron-right" size={14} className={styles.arrow} />
                        <Icon name="refresh" size={14} />
                        Build queued
                    </span>
                </div>

                <PreviewWindow title="pallet.dev/kilima-labs/apps/checkout-api" className={styles.window}>
                    <div className={styles.card}>
                        <div className={styles.cardTop}>
                            <div className={styles.cardName}>
                                <span className={styles.appName}>Checkout API</span>
                                <Badge tone="green" dot className={styles.live}>
                                    Connected
                                </Badge>
                            </div>
                            <span className={styles.region}>AWS · af-south-1</span>
                        </div>
                        <KeyValue className={styles.facts}>
                            <KeyValueRow term="Repository">
                                <span className={styles.repo}>
                                    <Icon name="github" size={15} />
                                    kilima-labs/checkout-api
                                </span>
                            </KeyValueRow>
                            <KeyValueRow term="Production branch">
                                <Badge outline className={styles.branch}>
                                    main
                                </Badge>
                            </KeyValueRow>
                            <KeyValueRow term="Latest push">
                                <span className={styles.fresh}>
                                    <Sha value="3f9c2e1" />
                                    <span className={styles.when}>· a few seconds ago</span>
                                </span>
                            </KeyValueRow>
                            <KeyValueRow term="Auto build">On for every push to main</KeyValueRow>
                        </KeyValue>
                    </div>
                </PreviewWindow>
            </div>
        </figure>
    );
}
