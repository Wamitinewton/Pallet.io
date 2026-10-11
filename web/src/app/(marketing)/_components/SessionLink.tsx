"use client";

import { authPaths, DEFAULT_SIGNED_IN_PATH, useSessionSummary } from "@/modules/session";
import { Button, type ButtonProps } from "@/shared/presentation/ui";
import Link from "next/link";

export type SessionLinkProps = Pick<ButtonProps, "variant" | "size" | "className">;

/** "Sign in" for a visitor, "Open dashboard" once `GET /api/session` says there is a session. */
export function SessionLink(props: SessionLinkProps) {
    const { isSuccess: signedIn } = useSessionSummary();
    return (
        <Button asChild {...props}>
            {signedIn ? (
                <Link href={DEFAULT_SIGNED_IN_PATH}>Open dashboard</Link>
            ) : (
                <Link href={authPaths.signIn()}>Sign in</Link>
            )}
        </Button>
    );
}
