"use client";

import { authPaths, DEFAULT_SIGNED_IN_PATH, useSessionSummary, useSignOut } from "@/modules/session";
import { messageFor } from "@/shared/presentation/errors";
import { useFocusOnMount } from "@/shared/presentation/hooks";
import { Button, Callout } from "@/shared/presentation/ui";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import type { InvitePreview, UnavailableReason } from "../domain/invite-preview";
import {
    accountExistsCopy,
    emailMismatchCopy,
    joinAsCopy,
    SIGN_IN_TO_ACCEPT_COPY,
    SIGN_OUT_COPY,
    signedInElsewhereCopy,
    signInToJoinCopy,
} from "./acceptance-copy";
import { invitePath } from "./invite-paths";
import styles from "./InviteAccept.module.css";
import { JoiningOrganization } from "./JoiningOrganization";
import { useAcceptAsSignedIn } from "./use-invite-acceptance";

export interface ExistingAccountPanelProps {
    readonly token: string;
    readonly preview: InvitePreview;
    readonly signedIn: boolean;
    /** Whether identity-service accepts an invite for a signed-in account yet (its checkpoint 15). */
    readonly canJoinAsSignedIn: boolean;
    readonly onUnavailable: (reason: UnavailableReason) => void;
    /** Takes focus when it replaces the new-account form. */
    readonly announce?: boolean;
}

/** For an invited address that already has an account: it signs in, then joins as itself. */
export function ExistingAccountPanel({
    token,
    preview,
    signedIn,
    canJoinAsSignedIn,
    onUnavailable,
    announce = false,
}: ExistingAccountPanelProps) {
    const panel = useFocusOnMount<HTMLDivElement>(announce);

    return (
        <div ref={panel} tabIndex={-1} className={styles.panel}>
            {!signedIn ? (
                <SignInToAccept token={token} preview={preview} canJoinAsSignedIn={canJoinAsSignedIn} />
            ) : canJoinAsSignedIn ? (
                <JoinAsSignedIn token={token} preview={preview} onUnavailable={onUnavailable} />
            ) : (
                <SignedInElsewhere token={token} preview={preview} />
            )}
        </div>
    );
}

interface PanelProps {
    readonly token: string;
    readonly preview: InvitePreview;
}

function SignInToAccept({ token, preview, canJoinAsSignedIn }: PanelProps & { readonly canJoinAsSignedIn: boolean }) {
    return (
        <>
            <Callout tone="blue" icon="info">
                {canJoinAsSignedIn ? signInToJoinCopy(preview.orgName) : accountExistsCopy(preview.orgName)}
            </Callout>
            <Button asChild variant="primary" size="lg" block>
                <Link href={authPaths.signIn({ next: invitePath(token) })}>{SIGN_IN_TO_ACCEPT_COPY}</Link>
            </Button>
        </>
    );
}

function SignOutButton({ token }: { readonly token: string }) {
    const { signOut, pending } = useSignOut(invitePath(token));
    return (
        <Button variant="secondary" size="lg" block onClick={signOut} loading={pending}>
            {SIGN_OUT_COPY}
        </Button>
    );
}

function SignedInElsewhere({ token, preview }: PanelProps) {
    const session = useSessionSummary();
    return (
        <>
            <Callout tone="blue" icon="info">
                {signedInElsewhereCopy(session.data?.email, preview.orgName)}
            </Callout>
            <SignOutButton token={token} />
        </>
    );
}

function JoinAsSignedIn({
    token,
    preview,
    onUnavailable,
}: PanelProps & Pick<ExistingAccountPanelProps, "onUnavailable">) {
    const router = useRouter();
    const session = useSessionSummary();
    const join = useAcceptAsSignedIn(token);
    const outcome = join.data;

    useEffect(() => {
        if (outcome?.kind === "unavailable") onUnavailable(outcome.reason);
        if (outcome?.kind === "accepted" && outcome.orgId === undefined) router.replace(DEFAULT_SIGNED_IN_PATH);
    }, [outcome, onUnavailable, router]);

    if (outcome?.kind === "accepted" && outcome.orgId !== undefined) {
        return <JoiningOrganization orgId={outcome.orgId} orgName={preview.orgName} />;
    }
    if (outcome?.kind === "email-mismatch") {
        return (
            <>
                <Callout tone="red" role="alert">
                    {emailMismatchCopy(preview.maskedEmail)}
                </Callout>
                <SignOutButton token={token} />
            </>
        );
    }
    if (outcome?.kind === "sign-in-required") {
        return <SignInToAccept token={token} preview={preview} canJoinAsSignedIn />;
    }

    return (
        <>
            {join.isError && (
                <Callout tone="red" role="alert">
                    {messageFor(join.error)}
                </Callout>
            )}
            <Button
                variant="primary"
                size="lg"
                block
                loading={join.isPending || outcome?.kind === "accepted"}
                onClick={() => {
                    join.mutate();
                }}
            >
                {joinAsCopy(preview.orgName, session.data?.email)}
            </Button>
        </>
    );
}
