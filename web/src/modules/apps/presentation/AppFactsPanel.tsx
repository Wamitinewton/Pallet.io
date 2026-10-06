import { Copyable, KeyValue, KeyValueRow, Panel, PanelBody, PanelHead } from "@/shared/presentation/ui";
import type { App } from "../domain/app";
import {
    APP_ID_LABEL_COPY,
    CLOUD_LABEL_COPY,
    CREATED_LABEL_COPY,
    createdOnCopy,
    FIXED_DESCRIPTION_COPY,
    FIXED_TITLE_COPY,
    PROVIDER_NAME,
    REGION_LABEL_COPY,
    regionLabel,
    SLUG_LABEL_COPY,
} from "./app-copy";
import styles from "./Apps.module.css";

export interface AppFactsPanelProps {
    readonly app: App;
}

/** What was chosen at creation, shown as facts rather than as inputs that can't be used. */
export function AppFactsPanel({ app }: AppFactsPanelProps) {
    return (
        <Panel aria-label={FIXED_TITLE_COPY}>
            <PanelHead title={FIXED_TITLE_COPY} description={FIXED_DESCRIPTION_COPY} />
            <PanelBody>
                <KeyValue>
                    <KeyValueRow term={SLUG_LABEL_COPY}>
                        <span className={styles.mono}>{app.slug}</span>
                    </KeyValueRow>
                    <KeyValueRow term={CLOUD_LABEL_COPY}>{PROVIDER_NAME[app.cloudProvider]}</KeyValueRow>
                    <KeyValueRow term={REGION_LABEL_COPY}>{regionLabel(app.cloudProvider, app.region)}</KeyValueRow>
                    <KeyValueRow term={APP_ID_LABEL_COPY}>
                        <Copyable value={app.id} label="app ID" />
                    </KeyValueRow>
                    <KeyValueRow term={CREATED_LABEL_COPY}>{createdOnCopy(app.createdAt)}</KeyValueRow>
                </KeyValue>
            </PanelBody>
        </Panel>
    );
}
