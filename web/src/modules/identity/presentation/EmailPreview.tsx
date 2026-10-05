import { BrandMark, Eyebrow, PreviewWindow, Stack, Text } from "@/shared/presentation/ui";
import styles from "./EmailPreview.module.css";

export function EmailPreview() {
    return (
        <Stack>
            <Eyebrow>What the email looks like</Eyebrow>
            <PreviewWindow title="Verify your Pallet email" className={styles.window}>
                <Stack className={styles.body}>
                    <div className={styles.from}>
                        <BrandMark />
                        <Text tone="ink">
                            <strong>Pallet</strong>
                        </Text>
                        <Text size="xs" tone="faint" className={styles.sender}>
                            no-reply@pallet.dev
                        </Text>
                    </div>
                    <p>Hi there, here&apos;s your code:</p>
                    <div className={styles.code}>K7QM 3WZP</div>
                    <Text size="sm" tone="muted">
                        It works once and expires soon. If you didn&apos;t sign up for Pallet, you can ignore this
                        email.
                    </Text>
                </Stack>
            </PreviewWindow>
        </Stack>
    );
}
