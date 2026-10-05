import { AuthColumn, AuthFoot, AuthSolo, Brand } from "@/shared/presentation/ui";
import Link from "next/link";
import type { ReactNode } from "react";

export function AuthSoloScreen({ children }: { readonly children: ReactNode }) {
    return (
        <AuthSolo>
            <Brand asChild>
                <Link href="/" />
            </Brand>
            <AuthColumn>{children}</AuthColumn>
            <AuthFoot>
                <span>© {new Date().getFullYear()} Pallet</span>
                <Link href="/">Back to pallet.dev</Link>
            </AuthFoot>
        </AuthSolo>
    );
}
