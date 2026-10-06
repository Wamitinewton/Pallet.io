import { Page, Panel, PanelBody, Skeleton, SkeletonText } from "@/shared/presentation/ui";

export default function DashboardLoading() {
    return (
        <Page aria-busy="true" aria-label="Loading">
            <Skeleton width={220} height={30} />
            <Skeleton width="45%" height={16} style={{ marginTop: 12, marginBottom: 28 }} />
            <Panel>
                <PanelBody>
                    <SkeletonText lines={4} />
                </PanelBody>
            </Panel>
        </Page>
    );
}
