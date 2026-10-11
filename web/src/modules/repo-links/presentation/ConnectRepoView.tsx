"use client";

import { appPath, appsPath, AppUnavailable, isAppUnavailable, useApp } from "@/modules/apps";
import { isGitHubSessionGone, useGitHubSession, useOrgInstallations, useStartAuthorization } from "@/modules/github";
import { useOrgAccess } from "@/modules/members";
import { organizationPath } from "@/modules/organizations";
import { ApiError } from "@/shared/domain/errors";
import type { AppId, InstallationId, OrgId } from "@/shared/domain/ids";
import { ErrorState, messageFor } from "@/shared/presentation/errors";
import { PageBreadcrumbs } from "@/shared/presentation/shell";
import {
    Button,
    Callout,
    Icon,
    Page,
    PageHead,
    Panel,
    PanelBody,
    PanelFoot,
    PanelHead,
    Skeleton,
    Stack,
    useToast,
} from "@/shared/presentation/ui";
import { zodResolver } from "@hookform/resolvers/zod";
import type { Route } from "next";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useQueryStates } from "nuqs";
import { useEffect, useState, type ReactNode } from "react";
import { useForm } from "react-hook-form";
import { buildSettingsSchema, newLinkSettings, type BuildSettingsForm } from "../domain/build-settings";
import type { PickerRepository } from "../domain/picker-repository";
import { isActive, type BuildSettings } from "../domain/repo-link";
import {
    BRANCH_NOT_FOUND,
    INSTALLATION_NOT_ACCESSIBLE,
    INSTALLATION_NOT_FOUND,
    INSTALLATION_SUSPENDED,
    INVALID_ROOT_DIRECTORY,
    REPO_LINK_EXISTS,
    REPOSITORY_ARCHIVED,
    REPOSITORY_NOT_ACCESSIBLE,
    REPOSITORY_PERMISSION_TOO_LOW,
} from "../domain/repo-link-errors";
import { BuildSettingsFields } from "./BuildSettingsFields";
import { InstallationPicker } from "./InstallationPicker";
import {
    ALREADY_LINKED_COPY,
    APPS_COPY,
    branchNotFoundCopy,
    CANCEL_COPY,
    CHOOSE_REPOSITORY_FIRST_COPY,
    connectDescriptionCopy,
    connectTitleCopy,
    CONTINUE_WITH_GITHUB_COPY,
    DEFAULT_BRANCH_HINT_COPY,
    LINK_A_REPOSITORY_COPY,
    LINK_AND_DEPLOY_COPY,
    LINK_ONLY_COPY,
    linkedCopy,
    linkErrorCopy,
    LOADING_CONNECT_COPY,
    PUSH_ACCESS_CHECKED_COPY,
    SESSION_ENDED_COPY,
    SESSION_UNAVAILABLE_COPY,
    signedInAsCopy,
    STEP_REPOSITORY_TITLE_COPY,
    STEP_SESSION_COPY,
    STEP_SESSION_DONE_TITLE_COPY,
    STEP_SESSION_TITLE_COPY,
    STEP_SETTINGS_TITLE_COPY,
    TRY_AGAIN_COPY,
} from "./repo-link-copy";
import { connectRepositoryPath } from "./repo-link-paths";
import styles from "./RepoLinks.module.css";
import { RepositoryPicker } from "./RepositoryPicker";
import { connectSearchParams } from "./search-params";
import { useRepoLink } from "./use-repo-link";
import { useLinkRepository } from "./use-repo-link-mutations";

export interface ConnectRepoViewProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    readonly appId: AppId;
}

/**
 * Admins only: anyone else, and an app that already has an active link, is sent to the app's page, where the
 * link is shown. The backend's `403` stays the authority either way.
 */
export function ConnectRepoView({ orgId, orgName, appId }: ConnectRepoViewProps) {
    const router = useRouter();
    const access = useOrgAccess();
    const app = useApp(orgId, appId);
    const link = useRepoLink(orgId, appId);
    const refused = access.role !== undefined && !access.can("repoLink.link");
    const alreadyLinked = link.data != null && isActive(link.data);
    const leaving = refused || alreadyLinked;

    useEffect(() => {
        if (leaving) router.replace(appPath(orgId, appId));
    }, [leaving, router, orgId, appId]);

    if (isAppUnavailable(app.error)) return <AppUnavailable orgId={orgId} />;

    const crumbs = [
        { label: orgName, href: organizationPath(orgId) as Route },
        { label: APPS_COPY, href: appsPath(orgId) },
        ...(app.data === undefined ? [] : [{ label: app.data.name, href: appPath(orgId, appId) }]),
        { label: LINK_A_REPOSITORY_COPY },
    ];

    const body = (): ReactNode => {
        if (app.isError && app.data === undefined) {
            return (
                <ErrorState
                    error={app.error}
                    headingLevel={2}
                    retrying={app.isFetching}
                    onRetry={() => void app.refetch()}
                />
            );
        }
        if (app.data === undefined || access.role === undefined || leaving) {
            return (
                <div role="status" aria-busy="true" aria-label={LOADING_CONNECT_COPY}>
                    <Skeleton width="60%" height={28} />
                </div>
            );
        }
        return (
            <>
                <PageHead title={connectTitleCopy(app.data.name)} description={connectDescriptionCopy(app.data.name)} />
                <ConnectSteps orgId={orgId} appId={appId} appName={app.data.name} />
            </>
        );
    };

    return (
        <Page narrow>
            <PageBreadcrumbs items={crumbs} />
            {body()}
        </Page>
    );
}

interface ConnectStepsProps {
    readonly orgId: OrgId;
    readonly appId: AppId;
    readonly appName: string;
}

/** Where a refusal of the link belongs on the page. */
type FailurePlace = "field" | "picker" | "installation" | "session" | "app" | "form";

function placeOf(error: unknown): FailurePlace {
    if (!(error instanceof ApiError)) return "form";
    if (isGitHubSessionGone(error)) return "session";
    if (error.is(BRANCH_NOT_FOUND) || error.is(INVALID_ROOT_DIRECTORY)) return "field";
    if (
        [REPOSITORY_PERMISSION_TOO_LOW, REPOSITORY_NOT_ACCESSIBLE, REPOSITORY_ARCHIVED].some((code) => error.is(code))
    ) {
        return "picker";
    }
    if ([INSTALLATION_SUSPENDED, INSTALLATION_NOT_FOUND, INSTALLATION_NOT_ACCESSIBLE].some((code) => error.is(code))) {
        return "installation";
    }
    if (error.is(REPO_LINK_EXISTS)) return "app";
    return "form";
}

function ConnectSteps({ orgId, appId, appName }: ConnectStepsProps) {
    const router = useRouter();
    const toast = useToast();
    const session = useGitHubSession();
    const installations = useOrgInstallations(orgId);
    const [params, setParams] = useQueryStates(connectSearchParams);
    const [selected, setSelected] = useState<PickerRepository>();
    const link = useLinkRepository(orgId, appId);
    const form = useForm<BuildSettingsForm, unknown, BuildSettings>({
        resolver: zodResolver(buildSettingsSchema),
        defaultValues: newLinkSettings(""),
    });

    useEffect(() => {
        if (params.github !== null) void setParams({ github: null });
    }, [params.github, setParams]);

    const usable = installations.data?.items.filter(({ status }) => status === "ACTIVE") ?? [];
    const installationId: InstallationId | undefined = (
        usable.find(({ installationId: id }) => id === params.installation) ?? usable[0]
    )?.installationId;
    const failurePlace = link.error === null ? undefined : placeOf(link.error);
    const linking = link.isPending ? (link.variables.deployNow ? "deploy" : "only") : undefined;

    const chooseInstallation = (id: InstallationId) => {
        setSelected(undefined);
        link.reset();
        void setParams({ installation: id, q: null, page: null });
    };

    const chooseRepository = (repository: PickerRepository) => {
        setSelected(repository);
        link.reset();
        form.setValue("productionBranch", repository.defaultBranch, { shouldDirty: true });
        form.clearErrors();
    };

    const linkWith = (deployNow: boolean) =>
        form.handleSubmit((settings) => {
            if (selected === undefined || installationId === undefined || link.isPending) return;
            link.mutate(
                { installationId, repository: selected, settings, deployNow },
                {
                    onSuccess: (created) => {
                        toast.success(linkedCopy(created.repoFullName, appName));
                        router.push(appPath(orgId, appId));
                    },
                    onError: (error) => {
                        if (!(error instanceof ApiError)) return;
                        if (error.is(BRANCH_NOT_FOUND)) {
                            form.setError(
                                "productionBranch",
                                { type: "server", message: branchNotFoundCopy(settings.productionBranch) },
                                { shouldFocus: true },
                            );
                        } else if (error.is(INVALID_ROOT_DIRECTORY)) {
                            form.setError(
                                "rootDirectory",
                                { type: "server", message: error.message },
                                { shouldFocus: true },
                            );
                        } else if (error.is(REPO_LINK_EXISTS)) {
                            toast.info(ALREADY_LINKED_COPY);
                            router.replace(appPath(orgId, appId));
                        }
                    },
                },
            );
        });

    if (session.data === undefined) {
        return session.isError ? (
            <Panel>
                <PanelBody className={styles.inlineError} role="alert">
                    <span>{SESSION_UNAVAILABLE_COPY}</span>
                    <Button
                        variant="secondary"
                        size="sm"
                        loading={session.isFetching}
                        onClick={() => void session.refetch()}
                    >
                        {TRY_AGAIN_COPY}
                    </Button>
                </PanelBody>
            </Panel>
        ) : (
            <div className={styles.steps} role="status" aria-label={LOADING_CONNECT_COPY}>
                <Skeleton height={64} />
                <Skeleton height={64} />
            </div>
        );
    }

    if (session.data === null) {
        return (
            <ol className={styles.steps}>
                <SignInStep orgId={orgId} appId={appId} sessionEnded={failurePlace === "session"} />
                <WaitingStep number={2} title={STEP_REPOSITORY_TITLE_COPY} />
                <WaitingStep number={3} title={STEP_SETTINGS_TITLE_COPY} />
            </ol>
        );
    }

    return (
        <ol className={styles.steps}>
            <Step state="done" number={1} title={STEP_SESSION_DONE_TITLE_COPY}>
                <span className={styles.stepSummary}>{signedInAsCopy(session.data.githubLogin)}</span>
            </Step>
            <Step state="current" number={2} title={STEP_REPOSITORY_TITLE_COPY}>
                <PanelBody>
                    <Stack>
                        {failurePlace === "installation" && (
                            <Callout tone="red" role="alert">
                                {messageFor(link.error, linkErrorCopy)}
                            </Callout>
                        )}
                        <InstallationPicker
                            orgId={orgId}
                            installations={installations}
                            value={installationId}
                            onChange={chooseInstallation}
                        />
                        {installationId !== undefined && (
                            <RepositoryPicker
                                key={installationId}
                                orgId={orgId}
                                installationId={installationId}
                                q={params.q}
                                page={params.page}
                                onSearch={(q) => void setParams({ q: q === "" ? null : q, page: null })}
                                onPageChange={(page) => void setParams({ page })}
                                selected={selected}
                                onSelect={chooseRepository}
                                notice={failurePlace === "picker" ? messageFor(link.error, linkErrorCopy) : undefined}
                            />
                        )}
                    </Stack>
                </PanelBody>
            </Step>
            <Step state="current" number={3} title={STEP_SETTINGS_TITLE_COPY} summary={selected?.fullName}>
                <form onSubmit={(event) => void linkWith(true)(event)} noValidate>
                    <PanelBody>
                        <Stack>
                            {failurePlace === "form" && (
                                <Callout tone="red" role="alert">
                                    {messageFor(link.error, linkErrorCopy)}
                                </Callout>
                            )}
                            <BuildSettingsFields
                                form={form}
                                branchHint={
                                    selected === undefined ? CHOOSE_REPOSITORY_FIRST_COPY : DEFAULT_BRANCH_HINT_COPY
                                }
                                disabled={selected === undefined}
                            />
                        </Stack>
                    </PanelBody>
                    <PanelFoot className={styles.footSpread}>
                        <span>{PUSH_ACCESS_CHECKED_COPY}</span>
                        <div className={styles.actions}>
                            <Button asChild variant="ghost" size="sm">
                                <Link href={appPath(orgId, appId)}>{CANCEL_COPY}</Link>
                            </Button>
                            <Button
                                variant="secondary"
                                size="sm"
                                loading={linking === "only"}
                                disabled={selected === undefined || linking === "deploy"}
                                onClick={(event) => void linkWith(false)(event)}
                            >
                                {LINK_ONLY_COPY}
                            </Button>
                            <Button
                                type="submit"
                                variant="primary"
                                size="sm"
                                loading={linking === "deploy"}
                                disabled={selected === undefined || linking === "only"}
                            >
                                <Icon name="link" />
                                {LINK_AND_DEPLOY_COPY}
                            </Button>
                        </div>
                    </PanelFoot>
                </form>
            </Step>
        </ol>
    );
}

interface SignInStepProps {
    readonly orgId: OrgId;
    readonly appId: AppId;
    readonly sessionEnded: boolean;
}

/** Off to GitHub and back here: the picker lists only what the person's own GitHub token reaches. */
function SignInStep({ orgId, appId, sessionEnded }: SignInStepProps) {
    const authorize = useStartAuthorization();

    return (
        <Step state="current" number={1} title={STEP_SESSION_TITLE_COPY}>
            <PanelBody>
                <Stack>
                    {sessionEnded && (
                        <Callout tone="amber" role="alert">
                            {SESSION_ENDED_COPY}
                        </Callout>
                    )}
                    {authorize.error !== null && (
                        <Callout tone="red" role="alert">
                            {messageFor(authorize.error, linkErrorCopy)}
                        </Callout>
                    )}
                    <p className={styles.muted}>{STEP_SESSION_COPY}</p>
                    <div>
                        <Button
                            variant="primary"
                            loading={authorize.pending}
                            onClick={() => {
                                authorize.start({ returnTo: connectRepositoryPath(orgId, appId) });
                            }}
                        >
                            <Icon name="github" />
                            {CONTINUE_WITH_GITHUB_COPY}
                        </Button>
                    </div>
                </Stack>
            </PanelBody>
        </Step>
    );
}

type StepState = "done" | "current" | "waiting";

interface StepProps {
    readonly state: StepState;
    readonly number: number;
    readonly title: string;
    /** Said at the right of the step's head, such as the repository chosen. */
    readonly summary?: string | undefined;
    readonly children?: ReactNode;
}

function Step({ state, number, title, summary, children }: StepProps) {
    const content = state === "done" ? undefined : children;
    return (
        <li>
            <Panel className={styles.step} data-step={state} aria-current={state === "current" ? "step" : undefined}>
                <PanelHead>
                    <div className={styles.stepHead}>
                        <span className={styles.stepNumber} aria-hidden="true">
                            {state === "done" ? <Icon name="check" /> : number}
                        </span>
                        <h2>{title}</h2>
                    </div>
                    {state === "done" && children}
                    {summary !== undefined && <span className={styles.stepSummaryMono}>{summary}</span>}
                </PanelHead>
                {content}
            </Panel>
        </li>
    );
}

function WaitingStep({ number, title }: Readonly<{ number: number; title: string }>) {
    return <Step state="waiting" number={number} title={title} />;
}
