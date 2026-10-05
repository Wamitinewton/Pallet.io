import type { SessionGateway } from "./ports";

export interface SignOutResult {
    readonly confirmed: boolean;
}

/** Never rejects: a failed request must not keep the person signed in on this device. */
export type SignOut = () => Promise<SignOutResult>;

export function makeSignOut(gateway: SessionGateway): SignOut {
    return async () => {
        try {
            await gateway.signOut();
            return { confirmed: true };
        } catch {
            return { confirmed: false };
        }
    };
}
