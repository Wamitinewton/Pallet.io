import type { InstallationId, OrgId } from "@/shared/domain/ids";
import type { AuthorizationGrant } from "./installation";
import { installationIdFrom } from "./installation";
import type { GitHubHandoff, ReturnIntent } from "./return-intent";

/** What GitHub's redirect back to `/github/callback` asks the dashboard to do. */
export type GitHubCallback =
    | { readonly kind: "authorization"; readonly grant: AuthorizationGrant }
    | { readonly kind: "freshInstall"; readonly installationId: InstallationId; readonly grant: AuthorizationGrant }
    /** The app is configured not to ask for user authorization during install, so linking needs a session. */
    | { readonly kind: "installWithoutAuthorization"; readonly installationId: InstallationId }
    /** A GitHub organization member asked their admin to approve the install. */
    | { readonly kind: "installRequested" }
    | { readonly kind: "cancelled" }
    | { readonly kind: "invalid" };

export const CALLBACK_PARAMS = ["code", "state", "installation_id", "setup_action", "error"] as const;

export type CallbackParam = (typeof CALLBACK_PARAMS)[number];

export type CallbackQuery = Readonly<Partial<Record<CallbackParam, string>>>;

const SETUP_ACTIONS: ReadonlySet<string> = new Set(["install", "update", "request"]);

/** Printable ASCII without spaces: GitHub's codes and the service's signed states are both far shorter. */
const OPAQUE_TOKEN = /^[\x21-\x7e]{1,1024}$/;

function tokenOrInvalid(value: string | undefined): string | null | undefined {
    if (value === undefined) return undefined;
    return OPAQUE_TOKEN.test(value) ? value : null;
}

/** Only the parameters GitHub sends, read from untrusted query text. */
export function callbackQueryFrom(params: URLSearchParams): CallbackQuery {
    const query: Partial<Record<CallbackParam, string>> = {};
    for (const name of CALLBACK_PARAMS) {
        const value = params.get(name);
        if (value !== null) query[name] = value;
    }
    return query;
}

export function classifyCallback(query: CallbackQuery): GitHubCallback {
    if (query.error !== undefined) return query.error === "access_denied" ? { kind: "cancelled" } : { kind: "invalid" };

    const setupAction = query.setup_action;
    if (setupAction !== undefined && !SETUP_ACTIONS.has(setupAction)) return { kind: "invalid" };
    if (setupAction === "request") return { kind: "installRequested" };

    const code = tokenOrInvalid(query.code);
    const state = tokenOrInvalid(query.state);
    if (code === null || state === null || (code === undefined) !== (state === undefined)) return { kind: "invalid" };
    const grant = code !== undefined && state !== undefined ? { code, state } : undefined;

    if (query.installation_id === undefined) {
        return grant === undefined ? { kind: "invalid" } : { kind: "authorization", grant };
    }
    const installationId = installationIdFrom(query.installation_id);
    if (installationId === undefined) return { kind: "invalid" };
    return grant === undefined
        ? { kind: "installWithoutAuthorization", installationId }
        : { kind: "freshInstall", installationId, grant };
}

/** What the callback page shows, or where it goes, once the redirect has been dealt with. */
export type GitHubRedirectOutcome =
    | { readonly kind: "connected"; readonly destination: string }
    | {
          readonly kind: "installed";
          readonly orgId: OrgId;
          readonly destination: string;
          readonly alreadyLinked: boolean;
      }
    /** Linking needs a GitHub session first: off to GitHub to authorize, then back to finish the link. */
    | { readonly kind: "authorizing"; readonly handoff: GitHubHandoff }
    | { readonly kind: "installRequested"; readonly backTo: string }
    | { readonly kind: "cancelled"; readonly backTo: string }
    | { readonly kind: "invalid"; readonly backTo: string }
    /** No intent recorded in this tab, or one too old to trust: the dashboard won't guess an organization. */
    | { readonly kind: "startAgain"; readonly backTo: string }
    | { readonly kind: "stateRejected"; readonly backTo: string; readonly retry: ReturnIntent }
    | { readonly kind: "failed"; readonly backTo: string; readonly retry: ReturnIntent; readonly error: unknown };
