"use client";

import { useSignOut } from "@/modules/session";
import { UserMenu } from "@/shared/presentation/shell";
import { accountPaths } from "./account-paths";
import { useMyProfile } from "./use-my-profile";

export function AccountMenu() {
    const profile = useMyProfile();
    const { signOut, pending } = useSignOut();

    return (
        <UserMenu
            name={profile?.displayName}
            email={profile?.email}
            accountHref={accountPaths.account}
            securityHref={accountPaths.security}
            onSignOut={signOut}
            signingOut={pending}
        />
    );
}
