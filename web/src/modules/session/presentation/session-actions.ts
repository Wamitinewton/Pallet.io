"use client";

import { createUseCasesContext } from "@/shared/presentation/providers";
import type { ConfirmPassword } from "../application/confirm-password";
import type { ReadSessionSummary } from "../application/read-session-summary";
import type { SignIn } from "../application/sign-in";
import type { SignOut } from "../application/sign-out";

export interface SessionActions {
    readonly signIn: SignIn;
    readonly signOut: SignOut;
    readonly readSummary: ReadSessionSummary;
    readonly confirmPassword: ConfirmPassword;
}

export const [SessionActionsProvider, useSessionActions] = createUseCasesContext<SessionActions>("SessionActions");
