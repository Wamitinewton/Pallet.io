import { Eyebrow, Stack } from "@/shared/presentation/ui";
import styles from "./SignupSteps.module.css";

const STEPS = [
    {
        title: "Confirm your email",
        detail: "We email you an 8-character code. You can sign in once it's confirmed.",
    },
    {
        title: "Connect GitHub",
        detail: "Install the Pallet GitHub App and choose which repositories it can see.",
    },
    {
        title: "Create your first app",
        detail: "Pick a cloud and region, link a repository, then push.",
    },
] as const;

export function SignupSteps() {
    return (
        <Stack>
            <Eyebrow>After you sign up</Eyebrow>
            <ol className={styles.steps}>
                {STEPS.map((step, index) => (
                    <li key={step.title} className={styles.step}>
                        <span className={styles.number} aria-hidden="true">
                            {index + 1}
                        </span>
                        <div>
                            <div className={styles.title}>{step.title}</div>
                            <div className={styles.detail}>{step.detail}</div>
                        </div>
                    </li>
                ))}
            </ol>
        </Stack>
    );
}
