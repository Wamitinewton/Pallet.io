import { SESSION_EXPIRED_CODE } from "@/shared/infrastructure/api/headers";

export const AUTHENTICATION_REQUIRED_CODE = "AUTHENTICATION_REQUIRED";
export const SESSION_EXPIRED_MESSAGE = "Your session has ended. Sign in again.";

export { SESSION_EXPIRED_CODE };

function errorCodeOf(body: string): unknown {
    try {
        const parsed: unknown = JSON.parse(body);
        return typeof parsed === "object" && parsed !== null ? (parsed as { error?: unknown }).error : undefined;
    } catch {
        return undefined;
    }
}

/**
 * A `401 AUTHENTICATION_REQUIRED` to a request that carried a bearer token means the gateway no longer
 * accepts that token: the session was revoked elsewhere (ADR-0015). Every other `401`, such as a wrong
 * current password, is an answer about the request, not about the session.
 */
export function isRevokedSessionAnswer(status: number, body: string, carriedToken: boolean): boolean {
    return carriedToken && status === 401 && errorCodeOf(body) === AUTHENTICATION_REQUIRED_CODE;
}
