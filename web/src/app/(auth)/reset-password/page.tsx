import { parseResetPasswordParams, ResetPasswordScreen } from "@/modules/identity";
import type { Metadata } from "next";

export const metadata: Metadata = {
    title: "Choose a new password",
    referrer: "no-referrer",
    robots: { index: false, follow: false },
};

export default async function ResetPasswordPage({ searchParams }: PageProps<"/reset-password">) {
    const { token } = parseResetPasswordParams(await searchParams);

    return <ResetPasswordScreen token={token} />;
}
