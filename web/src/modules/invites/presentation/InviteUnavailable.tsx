"use client";

import { authPaths, DEFAULT_SIGNED_IN_PATH } from "@/modules/session";
import { useFocusOnMount } from "@/shared/presentation/hooks";
import { AuthCard, AuthHeader, AuthIcon, Button } from "@/shared/presentation/ui";
import Link from "next/link";
import { useId } from "react";
import type { UnavailableReason } from "../domain/invite-preview";
import {
    EXPIRED_TITLE_COPY,
    expiredLede,
    OPEN_PALLET_COPY,
    SIGN_IN_COPY,
    VISIT_PALLET_COPY,
    WITHDRAWN_LEDE_COPY,
    WITHDRAWN_TITLE_COPY,
} from "./acceptance-copy";

export interface InviteUnavailableProps {
    readonly reason: UnavailableReason;
    /** Known once the invite has been previewed, so the expired copy can say whom to ask. */
    readonly inviterName?: string | undefined;
    readonly signedIn: boolean;
    /** Takes focus when it replaces a form the person was using. */
    readonly announce?: boolean;
}

export function InviteUnavailable({ reason, inviterName, signedIn, announce = false }: InviteUnavailableProps) {
    const card = useFocusOnMount<HTMLDivElement>(announce);
    const headerId = useId();

    if (reason === "expired") {
        return (
            <AuthCard ref={card} tabIndex={-1} aria-labelledby={headerId}>
                <AuthIcon name="clock" tone="amber" />
                <AuthHeader id={headerId} title={EXPIRED_TITLE_COPY} lede={expiredLede(inviterName)} />
                <Button asChild variant="secondary" block>
                    <Link href="/">{VISIT_PALLET_COPY}</Link>
                </Button>
            </AuthCard>
        );
    }

    return (
        <AuthCard ref={card} tabIndex={-1} aria-labelledby={headerId}>
            <AuthIcon name="x" />
            <AuthHeader id={headerId} title={WITHDRAWN_TITLE_COPY} lede={WITHDRAWN_LEDE_COPY} />
            <Button asChild variant="secondary" block>
                {signedIn ? (
                    <Link href={DEFAULT_SIGNED_IN_PATH}>{OPEN_PALLET_COPY}</Link>
                ) : (
                    <Link href={authPaths.signIn()}>{SIGN_IN_COPY}</Link>
                )}
            </Button>
        </AuthCard>
    );
}
