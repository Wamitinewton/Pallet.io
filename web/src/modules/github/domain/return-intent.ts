import { asInstallationId, asOrgId, type InstallationId, type OrgId } from "@/shared/domain/ids";
import { asIsoInstant, instantFromDate, type IsoInstant } from "@/shared/domain/instant";
import { DEFAULT_SIGNED_IN_PATH, safeRedirect } from "@/shared/domain/safe-redirect";
import { z } from "zod";

/** A GitHub page to send the browser to, and when the signed state inside its URL stops working. */
export interface GitHubHandoff {
    readonly url: string;
    readonly expiresAt: IsoInstant;
}

/**
 * Where the dashboard goes once GitHub sends the browser back. It holds no secret: GitHub's `state` binds
 * the round trip and the service verifies it, so this only says what the dashboard set out to do.
 */
export type ReturnIntent = AuthorizeIntent | InstallIntent;

export interface AuthorizeIntent {
    readonly kind: "authorize";
    readonly returnTo: string;
    readonly startedAt: IsoInstant;
    /** Set with `installationId` when authorizing is the step before linking an installation. */
    readonly orgId?: OrgId;
    readonly installationId?: InstallationId;
}

export interface InstallIntent {
    readonly kind: "install";
    readonly orgId: OrgId;
    readonly returnTo: string;
    readonly startedAt: IsoInstant;
}

export interface PendingLink {
    readonly orgId: OrgId;
    readonly installationId: InstallationId;
}

export const RETURN_INTENT_TTL_MS = 15 * 60 * 1000;

/** The query parameter a destination reads to say the round trip just finished. */
export const GITHUB_RETURN_PARAM = "github";

export const GITHUB_RETURN_FLAGS = ["connected", "installed"] as const;

export type GitHubReturnFlag = (typeof GITHUB_RETURN_FLAGS)[number];

const PROBE_ORIGIN = "http://pallet.invalid";

function isSafePath(path: string): boolean {
    return safeRedirect(path) === path;
}

const returnToSchema = z.string().refine(isSafePath);
const startedAtSchema = z.iso.datetime({ offset: true });

const storedIntentSchema = z.discriminatedUnion("kind", [
    z.object({
        kind: z.literal("authorize"),
        returnTo: returnToSchema,
        startedAt: startedAtSchema,
        orgId: z.string().min(1).optional(),
        installationId: z.number().int().positive().optional(),
    }),
    z.object({
        kind: z.literal("install"),
        orgId: z.string().min(1),
        returnTo: returnToSchema,
        startedAt: startedAtSchema,
    }),
]);

export function authorizeIntent(now: Date, returnTo: string, pending?: PendingLink): AuthorizeIntent {
    return {
        kind: "authorize",
        returnTo: safeRedirect(returnTo),
        startedAt: instantFromDate(now),
        ...(pending !== undefined && { orgId: pending.orgId, installationId: pending.installationId }),
    };
}

export function installIntent(now: Date, orgId: OrgId, returnTo: string): InstallIntent {
    return { kind: "install", orgId, returnTo: safeRedirect(returnTo), startedAt: instantFromDate(now) };
}

function isFresh(startedAt: string, now: Date): boolean {
    const age = now.getTime() - Date.parse(startedAt);
    return age >= 0 && age <= RETURN_INTENT_TTL_MS;
}

/**
 * The intent stored before leaving for GitHub, if it is well formed, points at a page on this site and was
 * recorded in the last fifteen minutes; anything else reads as no intent at all.
 */
export function readReturnIntent(stored: unknown, now: Date): ReturnIntent | undefined {
    const parsed = storedIntentSchema.safeParse(stored);
    if (!parsed.success || !isFresh(parsed.data.startedAt, now)) return undefined;
    const intent = parsed.data;
    const startedAt = asIsoInstant(intent.startedAt);

    if (intent.kind === "install") {
        return { kind: "install", orgId: asOrgId(intent.orgId), returnTo: intent.returnTo, startedAt };
    }
    if (intent.installationId !== undefined && intent.orgId === undefined) return undefined;
    return {
        kind: "authorize",
        returnTo: intent.returnTo,
        startedAt,
        ...(intent.orgId !== undefined && { orgId: asOrgId(intent.orgId) }),
        ...(intent.installationId !== undefined && { installationId: asInstallationId(intent.installationId) }),
    };
}

/** The installation an authorize intent was started for, when it was. */
export function pendingLinkOf(intent: ReturnIntent): PendingLink | undefined {
    if (intent.kind !== "authorize" || intent.orgId === undefined || intent.installationId === undefined) {
        return undefined;
    }
    return { orgId: intent.orgId, installationId: intent.installationId };
}

/** `returnTo` with the flag its page turns into the "back from GitHub" callout. */
export function returnDestination(intent: ReturnIntent, flag: GitHubReturnFlag): string {
    const url = new URL(intent.returnTo, PROBE_ORIGIN);
    url.searchParams.set(GITHUB_RETURN_PARAM, flag);
    return `${url.pathname}${url.search}${url.hash}`;
}

/** Where a person goes back to when the round trip went nowhere. */
export function backPath(intent: ReturnIntent | undefined): string {
    return intent?.returnTo ?? DEFAULT_SIGNED_IN_PATH;
}
