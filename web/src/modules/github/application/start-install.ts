import type { Clock } from "@/shared/domain/clock";
import type { OrgId } from "@/shared/domain/ids";
import { installIntent, type GitHubHandoff } from "../domain/return-intent";
import type { InstallationRepository, ReturnIntentStore } from "./ports";

export interface StartInstallCommand {
    readonly orgId: OrgId;
    readonly returnTo: string;
}

export type StartInstall = (command: StartInstallCommand) => Promise<GitHubHandoff>;

export function makeStartInstall(
    installations: InstallationRepository,
    intents: ReturnIntentStore,
    clock: Clock,
): StartInstall {
    return async ({ orgId, returnTo }) => {
        const handoff = await installations.startInstall(orgId);
        intents.save(installIntent(clock.now(), orgId, returnTo));
        return handoff;
    };
}
