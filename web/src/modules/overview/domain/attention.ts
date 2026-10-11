import type { AppId } from "@/shared/domain/ids";

export const MAX_ATTENTION_ITEMS = 5;

export type AttentionTone = "red" | "amber" | "blue";

export interface AppRef {
    readonly appId: AppId;
    readonly appName: string;
}

/** `Reason` is the repository-links module's disconnect reason, which this module only carries. */
export type AttentionItem<Reason> =
    | {
          readonly kind: "repository-disconnected";
          readonly app: AppRef;
          readonly repoFullName: string;
          readonly reason: Reason;
      }
    | { readonly kind: "repository-archived"; readonly app: AppRef; readonly repoFullName: string }
    | { readonly kind: "installation-suspended"; readonly accountLogin: string }
    | { readonly kind: "connect-github" }
    | { readonly kind: "link-repository"; readonly app: AppRef }
    | { readonly kind: "pending-invites"; readonly count: number }
    | { readonly kind: "unread-notifications"; readonly count: number };

export type AttentionKind = AttentionItem<unknown>["kind"];

export const ATTENTION_TONE: Readonly<Record<AttentionKind, AttentionTone>> = {
    "repository-disconnected": "red",
    "repository-archived": "amber",
    "installation-suspended": "amber",
    "connect-github": "blue",
    "link-repository": "blue",
    "pending-invites": "blue",
    "unread-notifications": "blue",
};

const SEVERITY: Readonly<Record<AttentionTone, number>> = { red: 0, amber: 1, blue: 2 };

export function toneOf(item: AttentionItem<unknown>): AttentionTone {
    return ATTENTION_TONE[item.kind];
}

/** Most severe first, keeping the given order within a tone, and no more than the panel shows. */
export function rankAttention<Reason>(items: readonly AttentionItem<Reason>[]): readonly AttentionItem<Reason>[] {
    return items.toSorted((a, b) => SEVERITY[toneOf(a)] - SEVERITY[toneOf(b)]).slice(0, MAX_ATTENTION_ITEMS);
}
