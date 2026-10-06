"use client";

import type { OrgId } from "@/shared/domain/ids";
import { messageFor } from "@/shared/presentation/errors";
import {
    Badge,
    Button,
    Callout,
    Choice,
    ChoiceGroup,
    Dialog,
    DialogBody,
    DialogClose,
    DialogFooter,
    Field,
    Icon,
    Spinner,
    Stack,
    Text,
    useToast,
} from "@/shared/presentation/ui";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useState, type ReactNode } from "react";
import {
    linkableInstallations,
    type InstallationLink,
    type Linkability,
    type LinkableInstallation,
} from "../domain/installation";
import {
    ACCOUNT_TYPE_LABEL,
    alreadyLinkedCopy,
    BACK_COPY,
    CHOOSE_DESCRIPTION_COPY,
    CHOOSE_LABEL_COPY,
    CHOOSE_TITLE_COPY,
    CONTINUE_COPY,
    CONTINUE_TO_GITHUB_COPY,
    EXISTING_CHOICE_COPY,
    EXISTING_CHOICE_TITLE_COPY,
    EXISTING_DESCRIPTION_COPY,
    EXISTING_SIGN_IN_COPY,
    EXISTING_TITLE_COPY,
    FIRST_PAGE_ONLY_COPY,
    INSTALL_CHOICE_COPY,
    INSTALL_CHOICE_TITLE_COPY,
    INSTALL_DESCRIPTION_COPY,
    INSTALL_INSTEAD_COPY,
    INSTALL_STEPS_COPY,
    INSTALL_TITLE_COPY,
    INSTALLATION_LABEL_COPY,
    LINK_INSTALLATION_COPY,
    LINKED_HERE_COPY,
    linkedCopy,
    linkErrorCopy,
    LOADING_VISIBLE_COPY,
    NO_VISIBLE_COPY,
    PERMISSIONS_COPY,
    SIGN_IN_WITH_GITHUB_COPY,
    startErrorCopy,
    SUSPENDED_COPY,
    TRY_AGAIN_COPY,
    visibleErrorCopy,
} from "./github-copy";
import { githubPath } from "./github-paths";
import { useGitHubUseCases } from "./github-use-cases";
import styles from "./GitHub.module.css";
import { GitHubAvatar } from "./GitHubAvatar";
import { githubQueries } from "./queries";
import { forgetGitHubSession, isGitHubSessionGone, useLinkInstallation } from "./use-github-mutations";
import { useStartAuthorization, useStartInstall } from "./use-github-redirect";
import { useGitHubSession } from "./use-github-session";

export type ConnectStep = "choose" | "install" | "existing";

const STEP_HEADING: Readonly<Record<ConnectStep, { title: string; description: string }>> = {
    choose: { title: CHOOSE_TITLE_COPY, description: CHOOSE_DESCRIPTION_COPY },
    install: { title: INSTALL_TITLE_COPY, description: INSTALL_DESCRIPTION_COPY },
    existing: { title: EXISTING_TITLE_COPY, description: EXISTING_DESCRIPTION_COPY },
};

export interface ConnectInstallationDialogProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    /** The step on screen; the dialog is open while one is set. */
    readonly step: ConnectStep | null;
    readonly onStepChange: (step: ConnectStep | null) => void;
    readonly trigger?: ReactNode;
}

export function ConnectInstallationDialog({
    orgId,
    orgName,
    step,
    onStepChange,
    trigger,
}: ConnectInstallationDialogProps) {
    const heading = STEP_HEADING[step ?? "choose"];
    const close = () => {
        onStepChange(null);
    };

    return (
        <Dialog
            open={step !== null}
            onOpenChange={(open) => {
                onStepChange(open ? "choose" : null);
            }}
            title={heading.title}
            description={heading.description}
            trigger={trigger}
            wide={step === "existing"}
        >
            {step === "choose" && <ChooseStep onChoose={onStepChange} />}
            {step === "install" && (
                <InstallStep
                    orgId={orgId}
                    onBack={() => {
                        onStepChange("choose");
                    }}
                />
            )}
            {step === "existing" && (
                <ExistingStep
                    orgId={orgId}
                    orgName={orgName}
                    onLinked={close}
                    onInstallInstead={() => {
                        onStepChange("install");
                    }}
                    onBack={() => {
                        onStepChange("choose");
                    }}
                />
            )}
        </Dialog>
    );
}

type ConnectChoice = Exclude<ConnectStep, "choose">;

function isConnectChoice(value: string): value is ConnectChoice {
    return value === "install" || value === "existing";
}

function ChooseStep({ onChoose }: Readonly<{ onChoose: (step: ConnectChoice) => void }>) {
    const [choice, setChoice] = useState<ConnectChoice>("install");

    return (
        <form
            noValidate
            onSubmit={(event) => {
                event.preventDefault();
                onChoose(choice);
            }}
        >
            <DialogBody>
                <Field label={CHOOSE_LABEL_COPY}>
                    <ChoiceGroup
                        value={choice}
                        onValueChange={(value) => {
                            if (isConnectChoice(value)) setChoice(value);
                        }}
                    >
                        <Choice value="install" title={INSTALL_CHOICE_TITLE_COPY} description={INSTALL_CHOICE_COPY} />
                        <Choice
                            value="existing"
                            title={EXISTING_CHOICE_TITLE_COPY}
                            description={EXISTING_CHOICE_COPY}
                        />
                    </ChoiceGroup>
                </Field>
            </DialogBody>
            <DialogFooter>
                <DialogClose>
                    <Button variant="secondary">Cancel</Button>
                </DialogClose>
                <Button type="submit" variant="primary">
                    {CONTINUE_COPY}
                </Button>
            </DialogFooter>
        </form>
    );
}

function InstallStep({ orgId, onBack }: Readonly<{ orgId: OrgId; onBack: () => void }>) {
    const install = useStartInstall();

    return (
        <>
            <DialogBody>
                <Stack>
                    {install.error !== null && (
                        <Callout tone="red" role="alert">
                            {messageFor(install.error, startErrorCopy)}
                        </Callout>
                    )}
                    <ol className={styles.steps}>
                        {INSTALL_STEPS_COPY.map((step) => (
                            <li key={step}>{step}</li>
                        ))}
                    </ol>
                    <Callout icon="shield">{PERMISSIONS_COPY}</Callout>
                </Stack>
            </DialogBody>
            <DialogFooter>
                <Button variant="secondary" onClick={onBack} disabled={install.pending}>
                    {BACK_COPY}
                </Button>
                <Button
                    variant="primary"
                    loading={install.pending}
                    onClick={() => {
                        install.start({ orgId, returnTo: githubPath(orgId) });
                    }}
                >
                    {CONTINUE_TO_GITHUB_COPY}
                    <Icon name="external" />
                </Button>
            </DialogFooter>
        </>
    );
}

interface ExistingStepProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    readonly onLinked: () => void;
    readonly onInstallInstead: () => void;
    readonly onBack: () => void;
}

function ExistingStep({ orgId, orgName, onLinked, onInstallInstead, onBack }: ExistingStepProps) {
    const session = useGitHubSession();
    const back = (
        <Button variant="secondary" onClick={onBack}>
            {BACK_COPY}
        </Button>
    );

    if (session.data === null) return <SignInToGitHub orgId={orgId} back={back} />;
    if (session.data === undefined) {
        return (
            <>
                <DialogBody>
                    {session.isError ? (
                        <Callout tone="red" role="alert">
                            {messageFor(session.error)}{" "}
                            <Button variant="ghost" size="sm" onClick={() => void session.refetch()}>
                                {TRY_AGAIN_COPY}
                            </Button>
                        </Callout>
                    ) : (
                        <Spinner label={LOADING_VISIBLE_COPY} />
                    )}
                </DialogBody>
                <DialogFooter>{back}</DialogFooter>
            </>
        );
    }
    return (
        <InstallationPicker
            orgId={orgId}
            orgName={orgName}
            onLinked={onLinked}
            onInstallInstead={onInstallInstead}
            back={back}
        />
    );
}

function SignInToGitHub({ orgId, back }: Readonly<{ orgId: OrgId; back: ReactNode }>) {
    const authorize = useStartAuthorization();

    return (
        <>
            <DialogBody>
                <Stack>
                    {authorize.error !== null && (
                        <Callout tone="red" role="alert">
                            {messageFor(authorize.error, startErrorCopy)}
                        </Callout>
                    )}
                    <Text>{EXISTING_SIGN_IN_COPY}</Text>
                </Stack>
            </DialogBody>
            <DialogFooter>
                {back}
                <Button
                    variant="primary"
                    loading={authorize.pending}
                    onClick={() => {
                        authorize.start({ returnTo: githubPath(orgId, { connect: "existing" }) });
                    }}
                >
                    <Icon name="github" />
                    {SIGN_IN_WITH_GITHUB_COPY}
                </Button>
            </DialogFooter>
        </>
    );
}

const NO_LINKS: readonly InstallationLink[] = [];

interface InstallationPickerProps {
    readonly orgId: OrgId;
    readonly orgName: string;
    readonly onLinked: () => void;
    readonly onInstallInstead: () => void;
    readonly back: ReactNode;
}

function InstallationPicker({ orgId, orgName, onLinked, onInstallInstead, back }: InstallationPickerProps) {
    const toast = useToast();
    const queryClient = useQueryClient();
    const { listVisibleInstallations, listOrgInstallations } = useGitHubUseCases();
    const visible = useQuery(githubQueries.visible(listVisibleInstallations));
    const linked = useQuery(githubQueries.installations(listOrgInstallations, orgId)).data?.items ?? NO_LINKS;
    const link = useLinkInstallation(orgId);
    const [chosen, setChosen] = useState("");

    const choices = useMemo(
        () =>
            linkableInstallations(
                visible.data?.items ?? [],
                new Set(linked.map((installation) => installation.installationId)),
            ),
        [visible.data, linked],
    );

    const sessionGone = visible.isError && isGitHubSessionGone(visible.error);
    useEffect(() => {
        if (sessionGone) forgetGitHubSession(queryClient);
    }, [sessionGone, queryClient]);

    const submit = () => {
        const choice = choices.find(({ installation }) => String(installation.installationId) === chosen);
        if (choice === undefined || choice.linkability === "suspended" || link.isPending) return;
        const login = choice.installation.accountLogin;
        link.mutate(
            { installationId: choice.installation.installationId },
            {
                onSuccess: ({ alreadyLinked }) => {
                    if (alreadyLinked) toast.info(alreadyLinkedCopy(login, orgName));
                    else toast.success(linkedCopy(login, orgName));
                    onLinked();
                },
            },
        );
    };

    const body = (): ReactNode => {
        if (visible.data === undefined) {
            return visible.isError ? (
                <Callout tone="red" role="alert">
                    {messageFor(visible.error, visibleErrorCopy)}{" "}
                    <Button variant="ghost" size="sm" onClick={() => void visible.refetch()}>
                        {TRY_AGAIN_COPY}
                    </Button>
                </Callout>
            ) : (
                <Spinner label={LOADING_VISIBLE_COPY} />
            );
        }
        if (choices.length === 0) {
            return (
                <Stack>
                    <Text>{NO_VISIBLE_COPY}</Text>
                    <div>
                        <Button variant="secondary" size="sm" onClick={onInstallInstead}>
                            {INSTALL_INSTEAD_COPY}
                        </Button>
                    </div>
                </Stack>
            );
        }
        return (
            <Stack>
                {link.isError && !isGitHubSessionGone(link.error) && (
                    <Callout tone="red" role="alert">
                        {messageFor(link.error, linkErrorCopy)}
                    </Callout>
                )}
                <Field label={INSTALLATION_LABEL_COPY}>
                    <ChoiceGroup value={chosen} onValueChange={setChosen}>
                        {choices.map((choice) => (
                            <InstallationChoice key={choice.installation.installationId} choice={choice} />
                        ))}
                    </ChoiceGroup>
                </Field>
                {!visible.data.isLast && <Text tone="muted">{FIRST_PAGE_ONLY_COPY}</Text>}
            </Stack>
        );
    };

    return (
        <form
            noValidate
            onSubmit={(event) => {
                event.preventDefault();
                submit();
            }}
        >
            <DialogBody>{body()}</DialogBody>
            <DialogFooter>
                {back}
                <Button type="submit" variant="primary" disabled={chosen === ""} loading={link.isPending}>
                    {LINK_INSTALLATION_COPY}
                </Button>
            </DialogFooter>
        </form>
    );
}

const LINKABILITY_BADGE: Readonly<Record<Linkability, ReactNode>> = {
    available: null,
    linkedHere: <Badge tone="blue">{LINKED_HERE_COPY}</Badge>,
    suspended: <Badge tone="amber">{SUSPENDED_COPY}</Badge>,
};

function InstallationChoice({ choice: { installation, linkability } }: Readonly<{ choice: LinkableInstallation }>) {
    const badge = LINKABILITY_BADGE[linkability];

    return (
        <Choice
            value={String(installation.installationId)}
            disabled={linkability === "suspended"}
            title={
                <span className={styles.choiceTitle}>
                    <GitHubAvatar login={installation.accountLogin} size={24} />
                    <span className={styles.login}>{installation.accountLogin}</span>
                    {badge !== null && <span className={styles.choiceBadge}>{badge}</span>}
                </span>
            }
            description={ACCOUNT_TYPE_LABEL[installation.accountType]}
        />
    );
}
