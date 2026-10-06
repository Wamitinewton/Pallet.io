import { requireActiveSession } from "@/composition/server";
import { GitHubCallback } from "@/modules/github";
import { SessionExpiryHandler } from "@/modules/session";
import { AuthSoloScreen } from "@/shared/presentation/shell";
import type { Metadata } from "next";

export const dynamic = "force-dynamic";

export const metadata: Metadata = {
    title: "Connecting GitHub",
    referrer: "no-referrer",
    robots: { index: false, follow: false },
};

/** GitHub's `code` and `state` are read in the browser only, so they never reach a server render or its payload. */
export default async function GitHubCallbackPage() {
    await requireActiveSession();

    return (
        <AuthSoloScreen>
            <SessionExpiryHandler />
            <GitHubCallback />
        </AuthSoloScreen>
    );
}
