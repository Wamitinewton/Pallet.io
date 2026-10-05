import { AuthFoot, AuthLayout, AuthMain, AuthSide, Brand } from "@/shared/presentation/ui";
import Link from "next/link";
import { SignupForm } from "./SignupForm";
import { SignupSteps } from "./SignupSteps";

export function SignupScreen() {
    return (
        <AuthLayout>
            <AuthMain>
                <Brand asChild>
                    <Link href="/" />
                </Brand>
                <SignupForm />
                <AuthFoot>
                    <span>© {new Date().getFullYear()} Pallet</span>
                    <Link href="/">Back to pallet.dev</Link>
                </AuthFoot>
            </AuthMain>
            <AuthSide aria-label="What happens next">
                <SignupSteps />
            </AuthSide>
        </AuthLayout>
    );
}
