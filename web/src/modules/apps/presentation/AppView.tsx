"use client";

import { useOrgAccess } from "@/modules/members";
import { useTeamDirectory } from "@/modules/teams";
import type { AppId, OrgId } from "@/shared/domain/ids";
import { ErrorState } from "@/shared/presentation/errors";
import { PageBreadcrumbs } from "@/shared/presentation/shell";
import {
    Icon,
    Page,
    SectionGap,
    Skeleton,
    Tabs,
    TabsContent,
    TabsList,
    TabsTrigger,
    VisuallyHidden,
} from "@/shared/presentation/ui";
import { useQuery } from "@tanstack/react-query";
import { useMemo, type ReactNode } from "react";
import { isAppUnavailable, type App } from "../domain/app";
import { APPS_TITLE_COPY, LOADING_APP_COPY, TAB_LABEL, TABS_LABEL_COPY, TEAM_LABEL_COPY } from "./app-copy";
import { appsPath, orgOverviewPath } from "./app-paths";
import { useAppUseCases } from "./app-use-cases";
import { AppFactsPanel } from "./AppFactsPanel";
import styles from "./Apps.module.css";
import { AppSettingsPanel } from "./AppSettingsPanel";
import { AppTeam, byTeamId } from "./AppTeam";
import { AppUnavailable } from "./AppUnavailable";
import { DeleteAppPanel } from "./DeleteAppPanel";
import { appQueries } from "./queries";
import { RunsOn } from "./RunsOn";
import { APP_PAGE_TABS, type AppPageTab } from "./search-params";
import { useAppTab } from "./use-app-params";

/**
 * What the repository-links module adds to the page; it depends on this module, so the page hands it in
 * rather than this module importing it.
 */
export interface AppViewSlots {
    /** The repository tab's content. Without it, the page shows the app's settings alone. */
    readonly repository?: ReactNode;
    /** Shown beside the app's name, such as starting a build. */
    readonly actions?: ReactNode;
}

export interface AppViewProps extends AppViewSlots {
    readonly orgId: OrgId;
    readonly orgName: string;
    readonly appId: AppId;
}

export function AppView({ orgId, orgName, appId, ...slots }: AppViewProps) {
    const { getApp } = useAppUseCases();
    const app = useQuery(appQueries.detail(getApp, orgId, appId));

    if (app.data !== undefined) return <AppPage orgId={orgId} orgName={orgName} app={app.data} {...slots} />;
    if (isAppUnavailable(app.error)) return <AppUnavailable orgId={orgId} />;

    return (
        <Page>
            <PageBreadcrumbs
                items={[
                    { label: orgName, href: orgOverviewPath(orgId) },
                    { label: APPS_TITLE_COPY, href: appsPath(orgId) },
                ]}
            />
            {app.isError ? (
                <ErrorState
                    error={app.error}
                    headingLevel={2}
                    retrying={app.isFetching}
                    onRetry={() => void app.refetch()}
                />
            ) : (
                <div role="status" aria-busy="true" aria-label={LOADING_APP_COPY}>
                    <Skeleton width="30%" height={28} />
                </div>
            )}
        </Page>
    );
}

interface AppPageProps extends AppViewSlots {
    readonly orgId: OrgId;
    readonly orgName: string;
    readonly app: App;
}

function isAppTab(value: string): value is AppPageTab {
    return (APP_PAGE_TABS as readonly string[]).includes(value);
}

function AppPage({ orgId, orgName, app, repository, actions }: AppPageProps) {
    const access = useOrgAccess();
    const [tab, selectTab] = useAppTab();
    const teams = useTeamDirectory(orgId).data;
    const teamsById = useMemo(() => (teams === undefined ? undefined : byTeamId(teams)), [teams]);

    const settings = (
        <SectionGap className={styles.settings}>
            {access.can("apps.update") && <AppSettingsPanel orgId={orgId} app={app} teams={teams} />}
            <AppFactsPanel app={app} />
            {access.can("apps.delete") && <DeleteAppPanel orgId={orgId} orgName={orgName} app={app} />}
        </SectionGap>
    );

    return (
        <Page>
            <PageBreadcrumbs
                items={[
                    { label: orgName, href: orgOverviewPath(orgId) },
                    { label: APPS_TITLE_COPY, href: appsPath(orgId) },
                    { label: app.name },
                ]}
            />
            <header className={styles.head}>
                <div className={styles.title}>
                    <span className={styles.appIcon} aria-hidden="true">
                        <Icon name="apps" />
                    </span>
                    <div>
                        <h1>{app.name}</h1>
                        <ul className={styles.meta}>
                            <li className={styles.mono}>{app.slug}</li>
                            <li>
                                <RunsOn app={app} />
                            </li>
                            <li>
                                <Icon name="teams" />
                                <VisuallyHidden>{TEAM_LABEL_COPY}: </VisuallyHidden>
                                <AppTeam orgId={orgId} teamId={app.teamId} teams={teamsById} link />
                            </li>
                        </ul>
                    </div>
                </div>
                {actions !== undefined && <div className={styles.headActions}>{actions}</div>}
            </header>

            {repository === undefined ? (
                settings
            ) : (
                <Tabs
                    value={tab}
                    onValueChange={(value) => {
                        if (isAppTab(value)) selectTab(value);
                    }}
                >
                    <TabsList aria-label={TABS_LABEL_COPY} className={styles.tabs}>
                        {APP_PAGE_TABS.map((value) => (
                            <TabsTrigger key={value} value={value}>
                                {TAB_LABEL[value]}
                            </TabsTrigger>
                        ))}
                    </TabsList>
                    <TabsContent value="repository">{repository}</TabsContent>
                    <TabsContent value="settings">{settings}</TabsContent>
                </Tabs>
            )}
        </Page>
    );
}
