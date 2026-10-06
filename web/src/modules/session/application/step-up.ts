import { requiresReauthentication } from "../domain/reauthentication";

/** Resolves true once the person has confirmed who they are, false when they decline. */
export type ConfirmIdentity = () => Promise<boolean>;

/**
 * Runs the action, and only when the backend answers that it needs a recent sign-in, confirms the person's
 * identity and runs it exactly once more. Declining rethrows the original error for the caller to handle.
 */
export async function withStepUp<T>(action: () => Promise<T>, confirmIdentity: ConfirmIdentity): Promise<T> {
    try {
        return await action();
    } catch (error) {
        if (!requiresReauthentication(error)) throw error;
        if (!(await confirmIdentity())) throw error;
        return action();
    }
}
