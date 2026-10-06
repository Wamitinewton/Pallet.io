"use client";

import { useSessionSummary } from "@/modules/session";
import { ErrorState, messageFor } from "@/shared/presentation/errors";
import {
    Badge,
    Button,
    Dialog,
    DialogClose,
    DialogFooter,
    Icon,
    List,
    ListItem,
    Panel,
    PanelFoot,
    PanelHead,
    Person,
    RelativeTime,
    Skeleton,
    Text,
    useToast,
} from "@/shared/presentation/ui";
import { useQuery } from "@tanstack/react-query";
import { useId, useState } from "react";
import { isCurrent, otherSessions, type AccountSession } from "../domain/account-session";
import {
    endOtherSessionsDescription,
    endSessionDescription,
    ONLY_THIS_DEVICE_COPY,
    OTHERS_NOT_ENDED_COPY,
    othersEndedCopy,
    SESSION_ENDED_COPY,
    SESSION_NOT_ENDED_COPY,
    sessionLabel,
    SESSIONS_DESCRIPTION_COPY,
    SESSIONS_FOOT_COPY,
    SESSIONS_TITLE_COPY,
    signOutOthersLabel,
    UNKNOWN_ADDRESS_COPY,
} from "./account-copy";
import styles from "./AccountView.module.css";
import { useIdentityUseCases } from "./identity-use-cases";
import { identityQueries } from "./queries";
import { useRevokeOtherSessions, useRevokeSession } from "./use-account";

const LOADING_ROWS = 2;

export function SessionsPanel() {
    const { listSessions } = useIdentityUseCases();
    const sessions = useQuery(identityQueries.sessions(listSessions));
    const summary = useSessionSummary();
    const headingId = useId();
    const [ending, setEnding] = useState<AccountSession>();
    const [endingOthers, setEndingOthers] = useState(false);

    const currentId = summary.data?.keycloakSessionId;
    const others = sessions.data === undefined ? [] : otherSessions(sessions.data, currentId);

    const body = () => {
        if (sessions.isError || summary.isError) {
            return (
                <ErrorState
                    error={sessions.error ?? summary.error}
                    headingLevel={3}
                    retrying={sessions.isFetching || summary.isFetching}
                    onRetry={() => {
                        if (sessions.isError) void sessions.refetch();
                        if (summary.isError) void summary.refetch();
                    }}
                />
            );
        }
        if (sessions.data === undefined || summary.data === undefined) return <LoadingRows />;
        return (
            <List aria-labelledby={headingId}>
                {sessions.data.map((session) => (
                    <SessionRow
                        key={session.id}
                        session={session}
                        current={isCurrent(session, currentId)}
                        onEnd={() => {
                            setEnding(session);
                        }}
                    />
                ))}
                {others.length === 0 && (
                    <ListItem>
                        <Text tone="muted" size="sm">
                            {ONLY_THIS_DEVICE_COPY}
                        </Text>
                    </ListItem>
                )}
            </List>
        );
    };

    return (
        <Panel aria-labelledby={headingId}>
            <PanelHead
                title={<span id={headingId}>{SESSIONS_TITLE_COPY}</span>}
                description={SESSIONS_DESCRIPTION_COPY}
            >
                {others.length > 0 && (
                    <Button
                        variant="danger-outline"
                        size="sm"
                        onClick={() => {
                            setEndingOthers(true);
                        }}
                    >
                        Sign out everywhere else
                    </Button>
                )}
            </PanelHead>
            {body()}
            <PanelFoot>
                <span>{SESSIONS_FOOT_COPY}</span>
            </PanelFoot>

            <EndSessionDialog
                session={ending}
                onClose={() => {
                    setEnding(undefined);
                }}
            />
            <EndOtherSessionsDialog
                open={endingOthers}
                count={others.length}
                onClose={() => {
                    setEndingOthers(false);
                }}
            />
        </Panel>
    );
}

interface SessionRowProps {
    readonly session: AccountSession;
    readonly current: boolean;
    readonly onEnd: () => void;
}

function SessionRow({ session, current, onEnd }: SessionRowProps) {
    return (
        <ListItem className={current ? styles.thisDevice : undefined}>
            <Person
                className={styles.session}
                avatar={
                    <span className={styles.device} aria-hidden="true">
                        <Icon name={current ? "monitor" : "globe"} />
                    </span>
                }
                name={current ? "This device" : "Another session"}
                meta={
                    <>
                        <Text mono>{session.ipAddress ?? UNKNOWN_ADDRESS_COPY}</Text>
                        {" · signed in "}
                        <RelativeTime dateTime={session.startedAt} />
                        {" · "}
                        {current ? (
                            "active now"
                        ) : (
                            <>
                                last active <RelativeTime dateTime={session.lastAccessedAt} />
                            </>
                        )}
                    </>
                }
            />
            {current ? (
                <Badge tone="green">Current</Badge>
            ) : (
                <Button
                    variant="ghost"
                    size="sm"
                    onClick={onEnd}
                    aria-label={`Sign out ${sessionLabel(session.ipAddress)}`}
                >
                    Sign out
                </Button>
            )}
        </ListItem>
    );
}

function LoadingRows() {
    return (
        <List aria-busy="true" aria-label="Loading sessions">
            {Array.from({ length: LOADING_ROWS }, (_, index) => (
                <ListItem key={index}>
                    <Skeleton width={36} height={36} />
                    <div className={styles.session}>
                        <Skeleton width="30%" height={14} />
                        <Skeleton width="60%" height={12} style={{ marginTop: 6 }} />
                    </div>
                </ListItem>
            ))}
        </List>
    );
}

interface EndSessionDialogProps {
    readonly session: AccountSession | undefined;
    readonly onClose: () => void;
}

/** Closes as soon as it is confirmed: the row leaves the list at once and comes back only if the request fails. */
function EndSessionDialog({ session, onClose }: EndSessionDialogProps) {
    const toast = useToast();
    const revoke = useRevokeSession();

    return (
        <Dialog
            open={session !== undefined}
            onOpenChange={(open) => {
                if (!open) onClose();
            }}
            title="Sign out this session?"
            description={endSessionDescription(session?.ipAddress)}
            onSubmit={() => {
                if (session === undefined) return;
                revoke.mutate(session.id, {
                    onSuccess: () => {
                        toast.success(SESSION_ENDED_COPY);
                    },
                    onError: (error) => {
                        toast.error(`${SESSION_NOT_ENDED_COPY} ${messageFor(error)}`);
                    },
                });
                onClose();
            }}
        >
            <DialogFooter>
                <DialogClose>
                    <Button variant="secondary">Cancel</Button>
                </DialogClose>
                <Button type="submit" variant="danger">
                    Sign out session
                </Button>
            </DialogFooter>
        </Dialog>
    );
}

interface EndOtherSessionsDialogProps {
    readonly open: boolean;
    readonly count: number;
    readonly onClose: () => void;
}

function EndOtherSessionsDialog({ open, count, onClose }: EndOtherSessionsDialogProps) {
    const toast = useToast();
    const revokeOthers = useRevokeOtherSessions();

    return (
        <Dialog
            open={open}
            onOpenChange={(next) => {
                if (!next && !revokeOthers.isPending) onClose();
            }}
            title="Sign out everywhere else?"
            description={endOtherSessionsDescription(count)}
            onSubmit={() => {
                if (revokeOthers.isPending) return;
                const ending = count;
                revokeOthers.mutate(undefined, {
                    onSuccess: () => {
                        toast.success(othersEndedCopy(ending));
                    },
                    onError: (error) => {
                        toast.error(`${OTHERS_NOT_ENDED_COPY} ${messageFor(error)}`);
                    },
                    onSettled: onClose,
                });
            }}
        >
            <DialogFooter>
                <DialogClose>
                    <Button variant="secondary" disabled={revokeOthers.isPending}>
                        Cancel
                    </Button>
                </DialogClose>
                <Button type="submit" variant="danger" loading={revokeOthers.isPending}>
                    {signOutOthersLabel(count)}
                </Button>
            </DialogFooter>
        </Dialog>
    );
}
