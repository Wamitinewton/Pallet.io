import { z } from "zod";
import { optionalBranchNameSchema } from "./branch-name";

/** What a manual build was asked for: no build-service exists yet, so this is all there is to show. */
export interface ManualBuild {
    /** Names the request: a retry with the same key answers with the same id. */
    readonly eventId: string;
    readonly commitSha: string;
    readonly branch: string;
}

/** An empty branch builds the production branch, which is then left out of the request. */
export const manualBuildSchema = z.object({ branch: optionalBranchNameSchema });

export type ManualBuildForm = z.input<typeof manualBuildSchema>;

export type ManualBuildValues = z.output<typeof manualBuildSchema>;

export interface ManualBuildRequest {
    readonly branch?: string;
}

/**
 * The branch the request names, or none for the production branch. Naming the production branch explicitly is
 * left out too, so it is one intent, and one idempotency key, with leaving the field as it was.
 */
export function requestedBranch(branch: string | undefined, productionBranch: string): string | undefined {
    return branch === undefined || branch === productionBranch ? undefined : branch;
}
