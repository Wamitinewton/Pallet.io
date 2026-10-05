import { parseEmail } from "@/shared/domain/email";
import { parseResetToken } from "./reset-password";

type SearchParams = Readonly<Record<string, string | string[] | undefined>>;

export interface ForgotPasswordParams {
    readonly email: string | undefined;
}

export function parseForgotPasswordParams(params: SearchParams): ForgotPasswordParams {
    return { email: parseEmail(params.email) };
}

export interface ResetPasswordParams {
    readonly token: string | undefined;
}

export function parseResetPasswordParams(params: SearchParams): ResetPasswordParams {
    return { token: parseResetToken(params.token) };
}
