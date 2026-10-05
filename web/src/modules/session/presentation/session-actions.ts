"use client";

import { createUseCasesContext } from "@/shared/presentation/providers";
import type { SignIn } from "../application/sign-in";
import type { SignOut } from "../application/sign-out";

export interface SessionActions {
    readonly signIn: SignIn;
    readonly signOut: SignOut;
}

export const [SessionActionsProvider, useSessionActions] = createUseCasesContext<SessionActions>("SessionActions");
