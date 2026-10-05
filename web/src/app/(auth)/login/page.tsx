import { hasActiveSession } from "@/composition/server";
import { parseSignInParams, safeRedirect, SignInScreen } from "@/modules/session";
import type { Metadata, Route } from "next";
import { redirect } from "next/navigation";

export const metadata: Metadata = {
    title: "Sign in",
};

export default async function LoginPage({ searchParams }: PageProps<"/login">) {
    const params = parseSignInParams(await searchParams);

    if (await hasActiveSession()) redirect(safeRedirect(params.next) as Route);

    return <SignInScreen {...params} />;
}
