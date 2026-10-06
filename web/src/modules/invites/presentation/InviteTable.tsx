"use client";

import type { MemberDirectory } from "@/modules/members";
import type { UserId } from "@/shared/domain/ids";
import { instantToDate } from "@/shared/domain/instant";
import { classifyRequestFailure } from "@/shared/domain/request-failure";
import type { Role } from "@/shared/domain/role";
import { useSecondsUntil } from "@/shared/presentation/hooks";
import { useClock } from "@/shared/presentation/providers";
import { RoleTag } from "@/shared/presentation/roles";
import {
    Badge,
    Button,
    RelativeTime,
    Skeleton,
    Table,
    TableWrap,
    Td,
    Th,
    VisuallyHidden,
    type BadgeTone,
} from "@/shared/presentation/ui";
import { useState } from "react";
import {
    canInviteAgain,
    canManageInvite,
    displayStatus,
    hasLapsed,
    isActionable,
    type Invite,
    type InviteStatus,
} from "../domain/invite";
import {
    COLUMN_LABEL,
    EXPIRED_COPY,
    FORMER_MEMBER_COPY,
    INVITED_BY_YOU_COPY,
    invitedByCopy,
    resendInCopy,
    sentCountCopy,
    STATUS_LABEL,
} from "./invite-copy";
import styles from "./Invites.module.css";
import { useInvites } from "./InvitesProvider";
import { useResendInvite } from "./use-invite-mutations";

export interface InviteViewer {
    readonly userId: UserId | undefined;
    readonly role: Role;
}

export interface InviteTableProps {
    readonly invites: readonly Invite[];
    readonly viewer: InviteViewer;
    /** Undefined until the members are read; no row names its inviter until then. */
    readonly directory: MemberDirectory | undefined;
    /** True while an earlier page stays on screen as the next one loads. */
    readonly busy?: boolean;
    readonly onRevoke: (invite: Invite) => void;
    readonly onInviteAgain: (invite: Invite) => void;
}

const STATUS_TONE: Readonly<Record<InviteStatus, { readonly tone: BadgeTone; readonly outline: boolean }>> = {
    PENDING: { tone: "blue", outline: false },
    ACCEPTED: { tone: "green", outline: false },
    REVOKED: { tone: "neutral", outline: true },
    EXPIRED: { tone: "neutral", outline: true },
};

export function InviteTable({ invites, viewer, directory, busy = false, onRevoke, onInviteAgain }: InviteTableProps) {
    const now = useClock().now();

    const invitedBy = (invite: Invite): string | undefined => {
        if (invite.invitedByUserId === viewer.userId) return INVITED_BY_YOU_COPY;
        if (directory === undefined) return undefined;
        return invitedByCopy(directory.get(invite.invitedByUserId)?.displayName ?? FORMER_MEMBER_COPY);
    };

    return (
        <TableWrap>
            <Table aria-label="Invites" aria-busy={busy} className={busy ? styles.stale : undefined}>
                <InviteTableHead />
                <tbody>
                    {invites.map((invite) => {
                        const status = displayStatus(invite, now);
                        const inviter = invitedBy(invite);
                        const manageable = canManageInvite(viewer.role, invite);
                        return (
                            <tr key={invite.id}>
                                <Td>
                                    <div className={styles.email} data-past={status !== "PENDING" || undefined}>
                                        {invite.email}
                                    </div>
                                    {inviter !== undefined && <div className={styles.meta}>{inviter}</div>}
                                </Td>
                                <Td>
                                    <RoleTag role={invite.role} />
                                </Td>
                                <Td>
                                    <Badge {...STATUS_TONE[status]}>{STATUS_LABEL[status]}</Badge>
                                </Td>
                                <Td hideOnMobile className={styles.small}>
                                    {sentCountCopy(invite.sendCount)}
                                </Td>
                                <Td hideOnMobile className={styles.small}>
                                    <Expiry invite={invite} now={now} />
                                </Td>
                                <Td actions>
                                    {manageable && isActionable(invite, now) && (
                                        <PendingInviteActions invite={invite} onRevoke={onRevoke} />
                                    )}
                                    {manageable && canInviteAgain(invite, now) && (
                                        <Button
                                            variant="secondary"
                                            size="sm"
                                            onClick={() => {
                                                onInviteAgain(invite);
                                            }}
                                        >
                                            Invite again<VisuallyHidden>: {invite.email}</VisuallyHidden>
                                        </Button>
                                    )}
                                </Td>
                            </tr>
                        );
                    })}
                </tbody>
            </Table>
        </TableWrap>
    );
}

/** When the link stops working; only pending and expired invites have one worth showing. */
function Expiry({ invite, now }: Readonly<{ invite: Invite; now: Date }>) {
    if (hasLapsed(invite, now)) return <span className={styles.lapsed}>{EXPIRED_COPY}</span>;
    if (invite.status !== "PENDING" && invite.status !== "EXPIRED") return null;
    return <RelativeTime dateTime={instantToDate(invite.expiresAt)} now={now} />;
}

interface PendingInviteActionsProps {
    readonly invite: Invite;
    readonly onRevoke: (invite: Invite) => void;
}

/** Each row resends on its own, so one row's cooldown or pending send never holds up another. */
function PendingInviteActions({ invite, onRevoke }: PendingInviteActionsProps) {
    const { orgId } = useInvites();
    const clock = useClock();
    const resend = useResendInvite(orgId);
    const [retryAt, setRetryAt] = useState<Date>();
    const wait = useSecondsUntil(retryAt);
    const coolingDown = wait > 0;

    return (
        <div className={styles.actions}>
            <Button
                variant="secondary"
                size="sm"
                loading={resend.isPending}
                aria-disabled={coolingDown || undefined}
                onClick={() => {
                    if (coolingDown || resend.isPending) return;
                    resend.mutate(invite, {
                        onError: (error) => {
                            const failure = classifyRequestFailure(error, clock.now());
                            if (failure.kind === "rate-limited") setRetryAt(failure.retryAt);
                        },
                    });
                }}
            >
                {coolingDown ? resendInCopy(wait) : "Resend"} <VisuallyHidden>invite to {invite.email}</VisuallyHidden>
            </Button>
            <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                    onRevoke(invite);
                }}
            >
                Revoke <VisuallyHidden>invite to {invite.email}</VisuallyHidden>
            </Button>
        </div>
    );
}

function InviteTableHead() {
    return (
        <thead>
            <tr>
                <Th>{COLUMN_LABEL.email}</Th>
                <Th>{COLUMN_LABEL.role}</Th>
                <Th>{COLUMN_LABEL.status}</Th>
                <Th hideOnMobile>{COLUMN_LABEL.sent}</Th>
                <Th hideOnMobile>{COLUMN_LABEL.expires}</Th>
                <Th actions>
                    <VisuallyHidden>Actions</VisuallyHidden>
                </Th>
            </tr>
        </thead>
    );
}

export function InviteTableSkeleton({ rows }: Readonly<{ rows: number }>) {
    return (
        <TableWrap>
            <Table aria-label="Loading invites" aria-busy="true">
                <InviteTableHead />
                <tbody>
                    {Array.from({ length: rows }, (_, index) => (
                        <tr key={index}>
                            <Td>
                                <div className={styles.skeletonText}>
                                    <Skeleton width="60%" height={14} />
                                    <Skeleton width="40%" height={12} />
                                </div>
                            </Td>
                            <Td>
                                <Skeleton width={72} height={22} />
                            </Td>
                            <Td>
                                <Skeleton width={64} height={22} />
                            </Td>
                            <Td hideOnMobile>
                                <Skeleton width={72} height={12} />
                            </Td>
                            <Td hideOnMobile>
                                <Skeleton width={64} height={12} />
                            </Td>
                            <Td actions />
                        </tr>
                    ))}
                </tbody>
            </Table>
        </TableWrap>
    );
}
