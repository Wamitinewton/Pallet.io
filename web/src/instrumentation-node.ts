import { InvalidServerEnvError, serverEnv } from "@/shared/infrastructure/config/server-env";

export function validateServerEnv(): void {
    try {
        serverEnv();
    } catch (error) {
        if (!(error instanceof InvalidServerEnvError)) throw error;
        process.stderr.write(`${error.message}\n`);
        process.exit(1);
    }
}
