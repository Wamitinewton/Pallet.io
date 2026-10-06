import { Icon } from "@/shared/presentation/ui";
import type { App } from "../domain/app";
import { PROVIDER_SHORT_NAME, regionLabel } from "./app-copy";
import styles from "./Apps.module.css";

export interface RunsOnProps {
    readonly app: Pick<App, "cloudProvider" | "region">;
}

/** The cloud and region, fixed at creation; the region's place is in its tooltip. */
export function RunsOn({ app }: RunsOnProps) {
    return (
        <span className={styles.runsOn}>
            <Icon name="cloud" />
            {PROVIDER_SHORT_NAME[app.cloudProvider]} ·{" "}
            <span className={styles.region} title={regionLabel(app.cloudProvider, app.region)}>
                {app.region}
            </span>
        </span>
    );
}
