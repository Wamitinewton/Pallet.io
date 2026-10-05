import { credentialsSchema, type Credentials } from "../domain/credentials";
import type { SessionGateway } from "./ports";

export type SignIn = (credentials: Credentials) => Promise<void>;

export function makeSignIn(gateway: SessionGateway): SignIn {
    return async (credentials) => {
        await gateway.signIn(credentialsSchema.parse(credentials));
    };
}
