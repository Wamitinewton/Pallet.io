import { AuthFoot, AuthLayout, AuthMain, AuthSide, Brand } from "@/shared/presentation/ui";
import Link from "next/link";
import { LoginForm, type LoginFormProps } from "./LoginForm";
import { SignInPreview } from "./SignInPreview";

export function SignInScreen(props: LoginFormProps) {
    return (
        <AuthLayout>
            <AuthMain>
                <Brand asChild>
                    <Link href="/" />
                </Brand>
                <LoginForm {...props} />
                <AuthFoot>
                    <span>© {new Date().getFullYear()} Pallet</span>
                    <Link href="/">Back to pallet.dev</Link>
                </AuthFoot>
            </AuthMain>
            <AuthSide aria-label="Product preview">
                <SignInPreview />
            </AuthSide>
        </AuthLayout>
    );
}
