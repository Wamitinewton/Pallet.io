import type { ReturnIntent } from "@/modules/github/domain/return-intent";
import { sessionStorageReturnIntentStore } from "@/modules/github/infrastructure/session-storage-return-intent-store";

/** This tab's intent, as the dashboard records it before leaving for GitHub. */
export const browserReturnIntents = sessionStorageReturnIntentStore();

export function storedReturnIntent(): ReturnIntent | undefined {
    return browserReturnIntents.read() as ReturnIntent | undefined;
}
