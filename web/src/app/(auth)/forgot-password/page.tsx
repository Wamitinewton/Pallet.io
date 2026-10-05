import { ForgotPasswordScreen, parseForgotPasswordParams } from "@/modules/identity";
import type { Metadata } from "next";

export const metadata: Metadata = {
    title: "Reset your password",
};

export default async function ForgotPasswordPage({ searchParams }: PageProps<"/forgot-password">) {
    const { email } = parseForgotPasswordParams(await searchParams);

    return <ForgotPasswordScreen key={email} email={email} />;
}
