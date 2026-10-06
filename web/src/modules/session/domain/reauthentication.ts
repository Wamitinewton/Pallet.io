import { ApiError } from "@/shared/domain/errors";
import { z } from "zod";

export const REAUTHENTICATION_REQUIRED = "REAUTHENTICATION_REQUIRED";
export const IDENTITY_MISMATCH = "IDENTITY_MISMATCH";

/** No length or strength rule, for the same reason as sign-in: the account may predate the policy. */
export const reauthenticationSchema = z.object({
    password: z.string().min(1, "Enter your password"),
});

export type Reauthentication = z.output<typeof reauthenticationSchema>;

/** The backend's answer to an action that needs a sign-in more recent than the session's. */
export function requiresReauthentication(error: unknown): boolean {
    return error instanceof ApiError && error.status === 403 && error.is(REAUTHENTICATION_REQUIRED);
}
