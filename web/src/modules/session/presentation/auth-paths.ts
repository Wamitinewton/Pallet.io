import type { Route } from "next";
import { signInPath, type SignInPathOptions } from "../domain/sign-in-path";

const route = (path: string) => path as Route;

function withEmail(path: string, email: string | undefined): Route {
    const trimmed = email?.trim() ?? "";
    return route(trimmed === "" ? path : `${path}?${new URLSearchParams({ email: trimmed }).toString()}`);
}

export const authPaths = {
    signIn: (options?: SignInPathOptions) => route(signInPath(options)),
    signUp: route("/signup"),
    forgotPassword: (email?: string) => withEmail("/forgot-password", email),
    resetPassword: route("/reset-password"),
    verifyEmail: (email: string) => withEmail("/verify-email", email),
};
