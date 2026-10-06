import { reauthenticationSchema, type Reauthentication } from "../domain/reauthentication";
import type { SessionGateway } from "./ports";

/** The browser's half of step-up: the BFF signs in again and keeps the same session cookie. */
export type ConfirmPassword = (reauthentication: Reauthentication) => Promise<void>;

export function makeConfirmPassword(gateway: SessionGateway): ConfirmPassword {
    return async (reauthentication) => {
        await gateway.reauthenticate(reauthenticationSchema.parse(reauthentication));
    };
}
