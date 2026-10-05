import { authPaths } from "@/modules/session";
import { AuthFoot, AuthLayout, AuthMain, AuthSide, Brand } from "@/shared/presentation/ui";
import Link from "next/link";
import { EmailPreview } from "./EmailPreview";
import { VerifyEmailForm, type VerifyEmailFormProps } from "./VerifyEmailForm";

export function VerifyEmailScreen({ email }: VerifyEmailFormProps) {
    return (
        <AuthLayout>
            <AuthMain>
                <Brand asChild>
                    <Link href="/" />
                </Brand>
                <VerifyEmailForm email={email} />
                <AuthFoot>
                    <span>© {new Date().getFullYear()} Pallet</span>
                    <Link href={authPaths.signIn()}>Sign in instead</Link>
                </AuthFoot>
            </AuthMain>
            <AuthSide aria-label="Email preview">
                <EmailPreview />
            </AuthSide>
        </AuthLayout>
    );
}
