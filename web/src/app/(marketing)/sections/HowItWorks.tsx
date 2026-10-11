import { Icon, type IconName } from "@/shared/presentation/ui";
import type { ReactNode } from "react";
import { Band, BandHead, Reveal } from "../_components/Band";
import { SECTION_ID } from "../_components/landing-links";
import styles from "./HowItWorks.module.css";

interface Step {
    readonly icon: IconName;
    readonly title: string;
    readonly body: ReactNode;
}

const STEPS: readonly Step[] = [
    {
        icon: "github",
        title: "Install the GitHub App",
        body: "An admin installs Pallet on your GitHub account or organization and picks which repositories it can see. You can change that list on GitHub at any time.",
    },
    {
        icon: "link",
        title: "Link a repository to an app",
        body: (
            <>
                Choose the repo, the production branch and the folder your app lives in, such as <code>apps/api</code>{" "}
                in a monorepo. Pallet checks that you have push access on GitHub before it saves the link.
            </>
        ),
    },
    {
        icon: "branch",
        title: "Push",
        body: "Each push to the production branch is recorded and queues a build. If you'd rather decide when, turn off auto build and request one by hand from the app page.",
    },
];

export function HowItWorks() {
    return (
        <Band id={SECTION_ID.how} tint>
            <BandHead id={SECTION_ID.how} eyebrow="How it works" title="From repository to queued build in three steps">
                <p>You do the first two once per app. After that, pushing to your production branch is all it takes.</p>
            </BandHead>
            <Reveal>
                <ol className={styles.steps}>
                    {STEPS.map((step, index) => (
                        <li key={step.title} className={styles.step}>
                            <div className={styles.marker}>
                                <span className={styles.number}>{index + 1}</span>
                                <Icon name={step.icon} size={18} className={styles.icon} />
                            </div>
                            <h3 className={styles.title}>{step.title}</h3>
                            <p className={styles.body}>{step.body}</p>
                        </li>
                    ))}
                </ol>
            </Reveal>
        </Band>
    );
}
