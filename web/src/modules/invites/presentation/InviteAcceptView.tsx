"use client";

import { ErrorState } from "@/shared/presentation/errors";
import { AuthCard, Skeleton, SkeletonText, VisuallyHidden } from "@/shared/presentation/ui";
import { useCallback, useId, useState } from "react";
import type { InvitePreview, UnavailableReason } from "../domain/invite-preview";
import { LOADING_INVITE_COPY, PREVIEW_FAILED_TITLE_COPY } from "./acceptance-copy";
import { ExistingAccountPanel } from "./ExistingAccountPanel";
import { InviteSummary } from "./InviteSummary";
import { InviteUnavailable } from "./InviteUnavailable";
import { NewAccountForm } from "./NewAccountForm";
import { useInvitePreview, type UnacceptedOutcome } from "./use-invite-acceptance";

export interface InviteAcceptViewProps {
    readonly token: string;
    readonly signedIn: boolean;
    readonly canJoinAsSignedIn: boolean;
}

export function InviteAcceptView({ token, signedIn, canJoinAsSignedIn }: InviteAcceptViewProps) {
    const preview = useInvitePreview(token);

    if (preview.isPending) return <InviteLoading />;
    if (preview.isError) {
        return (
            <AuthCard>
                <ErrorState
                    error={preview.error}
                    title={PREVIEW_FAILED_TITLE_COPY}
                    onRetry={() => void preview.refetch()}
                    retrying={preview.isFetching}
                />
            </AuthCard>
        );
    }
    if (preview.data.kind === "unavailable") {
        return <InviteUnavailable reason={preview.data.reason} signedIn={signedIn} />;
    }
    return (
        <OpenInvite
            token={token}
            preview={preview.data.preview}
            signedIn={signedIn}
            canJoinAsSignedIn={canJoinAsSignedIn}
        />
    );
}

type Stage =
    | { readonly kind: "new" }
    | { readonly kind: "existing"; readonly announce: boolean }
    | { readonly kind: "unavailable"; readonly reason: UnavailableReason };

interface OpenInviteProps extends InviteAcceptViewProps {
    readonly preview: InvitePreview;
}

function stageAfter(outcome: UnacceptedOutcome): Stage {
    switch (outcome.kind) {
        case "unavailable":
            return { kind: "unavailable", reason: outcome.reason };
        case "sign-in-required":
        case "email-mismatch":
            return { kind: "existing", announce: true };
    }
}

/** A signed-out visitor creates an account; a signed-in one, or an address that already has one, signs in to join. */
function OpenInvite({ token, preview, signedIn, canJoinAsSignedIn }: OpenInviteProps) {
    const headingId = useId();
    const [stage, setStage] = useState<Stage>(() =>
        signedIn ? { kind: "existing", announce: false } : { kind: "new" },
    );
    const becomeUnavailable = useCallback((reason: UnavailableReason) => {
        setStage({ kind: "unavailable", reason });
    }, []);

    if (stage.kind === "unavailable") {
        return (
            <InviteUnavailable reason={stage.reason} inviterName={preview.inviterName} signedIn={signedIn} announce />
        );
    }

    return (
        <AuthCard asChild>
            <section aria-labelledby={headingId}>
                <InviteSummary preview={preview} headingId={headingId} />
                {stage.kind === "new" ? (
                    <NewAccountForm
                        token={token}
                        orgName={preview.orgName}
                        onOutcome={(outcome) => {
                            setStage(stageAfter(outcome));
                        }}
                    />
                ) : (
                    <ExistingAccountPanel
                        token={token}
                        preview={preview}
                        signedIn={signedIn}
                        canJoinAsSignedIn={canJoinAsSignedIn}
                        onUnavailable={becomeUnavailable}
                        announce={stage.announce}
                    />
                )}
            </section>
        </AuthCard>
    );
}

function InviteLoading() {
    return (
        <AuthCard aria-busy="true">
            <VisuallyHidden role="status">{LOADING_INVITE_COPY}</VisuallyHidden>
            <Skeleton width={56} height={56} />
            <SkeletonText lines={3} />
        </AuthCard>
    );
}
