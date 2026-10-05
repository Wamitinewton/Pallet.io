import { hasActiveSession } from "@/composition/server";
import { SignupScreen } from "@/modules/identity";
import { DEFAULT_SIGNED_IN_PATH } from "@/modules/session";
import type { Metadata, Route } from "next";
import { redirect } from "next/navigation";

export const dynamic = "force-dynamic";

export const metadata: Metadata = {
    title: "Create your organization",
};

export default async function SignupPage() {
    if (await hasActiveSession()) redirect(DEFAULT_SIGNED_IN_PATH as Route);

    return <SignupScreen />;
}
