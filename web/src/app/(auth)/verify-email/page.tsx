import { parseVerifyEmailParams, VerifyEmailScreen } from "@/modules/identity";
import type { Metadata } from "next";

export const metadata: Metadata = {
    title: "Confirm your email",
};

export default async function VerifyEmailPage({ searchParams }: PageProps<"/verify-email">) {
    const { email } = parseVerifyEmailParams(await searchParams);

    return <VerifyEmailScreen key={email} email={email} />;
}
