import { Badge, Eyebrow, Icon, List, ListItem, Person, PreviewWindow, Stack, Text } from "@/shared/presentation/ui";
import styles from "./SignInPreview.module.css";

const SAMPLE_APPS = [
    { name: "Checkout API", source: "kilima-labs/checkout-api · main", connected: true },
    { name: "Merchant dashboard", source: "kilima-labs/web · main", connected: true },
    { name: "Receipts worker", source: "kilima-labs/receipts · release", connected: false },
] as const;

export function SignInPreview() {
    return (
        <Stack className={styles.preview}>
            <Eyebrow>Kilima Labs · Apps</Eyebrow>
            <PreviewWindow>
                <List>
                    {SAMPLE_APPS.map((app) => (
                        <ListItem key={app.name}>
                            <Person
                                className={styles.app}
                                avatar={
                                    <span className={styles.appIcon}>
                                        <Icon name="apps" />
                                    </span>
                                }
                                name={app.name}
                                meta={<Text mono>{app.source}</Text>}
                            />
                            {app.connected ? (
                                <Badge tone="green" dot>
                                    Connected
                                </Badge>
                            ) : (
                                <Badge tone="amber">Needs attention</Badge>
                            )}
                        </ListItem>
                    ))}
                </List>
            </PreviewWindow>
            <Text tone="muted">
                Every app, repository and teammate in one place. Pallet tells you when a link breaks and how to fix it.
            </Text>
        </Stack>
    );
}
