import Link from "next/link";
import type { ReactNode } from "react";
import { AuthColumn, AuthFoot, AuthSolo, Brand } from "../ui";

export interface AuthSoloScreenProps {
    readonly children: ReactNode;
    readonly footLink?: ReactNode;
}

/** A single card on the brand background, for the signed-out screens that need no product preview. */
export function AuthSoloScreen({ children, footLink = "Back to pallet.dev" }: AuthSoloScreenProps) {
    return (
        <AuthSolo>
            <Brand asChild>
                <Link href="/" />
            </Brand>
            <AuthColumn>{children}</AuthColumn>
            <AuthFoot>
                <span>© {new Date().getFullYear()} Pallet</span>
                <Link href="/">{footLink}</Link>
            </AuthFoot>
        </AuthSolo>
    );
}
