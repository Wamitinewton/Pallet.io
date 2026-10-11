import { asAppId } from "@/shared/domain/ids";
import { describe, expect, it } from "vitest";
import { MAX_ATTENTION_ITEMS, toneOf, type AppRef } from "../domain/attention";
import {
    buildAttentionList,
    type AttentionInput,
    type RecentAppState,
    type RepositoryState,
} from "./build-attention-list";

type Reason = "REPOSITORY_ACCESS_REMOVED" | "REPOSITORY_DELETED";

const CHECKOUT: AppRef = { appId: asAppId("app-checkout"), appName: "Checkout API" };
const RECEIPTS: AppRef = { appId: asAppId("app-receipts"), appName: "Receipts worker" };
const DOCS: AppRef = { appId: asAppId("app-docs"), appName: "Docs" };

const connected: RepositoryState<Reason> = {
    kind: "connected",
    repoFullName: "kilima-labs/checkout-api",
    archived: false,
};

function app(ref: AppRef, repository: RepositoryState<Reason> = connected): RecentAppState<Reason> {
    return { app: ref, repository };
}

/** An organization with nothing wrong in it, as an admin sees it. */
const CALM: AttentionInput<Reason> = {
    apps: [app(CHECKOUT)],
    installations: [{ accountLogin: "kilima-labs", suspended: false }],
    canManageInstallations: true,
    pendingInvites: 0,
    unreadNotifications: 0,
};

function attention(change: Partial<AttentionInput<Reason>>) {
    return buildAttentionList({ ...CALM, ...change });
}

describe("buildAttentionList", () => {
    it("has nothing to say about a calm organization", () => {
        expect(buildAttentionList(CALM)).toEqual([]);
    });

    it("says nothing about what hasn't loaded, failed, or isn't the caller's to read", () => {
        expect(
            buildAttentionList({
                apps: undefined,
                installations: undefined,
                canManageInstallations: true,
                pendingInvites: undefined,
                unreadNotifications: undefined,
            }),
        ).toEqual([]);
    });

    it("names an app whose repository was disconnected, with the reason, in red", () => {
        const items = attention({
            apps: [
                app(RECEIPTS, {
                    kind: "disconnected",
                    repoFullName: "kilima-labs/receipts",
                    reason: "REPOSITORY_ACCESS_REMOVED",
                }),
            ],
        });

        expect(items).toEqual([
            {
                kind: "repository-disconnected",
                app: RECEIPTS,
                repoFullName: "kilima-labs/receipts",
                reason: "REPOSITORY_ACCESS_REMOVED",
            },
        ]);
        expect(items.map(toneOf)).toEqual(["red"]);
    });

    it("names an app whose repository is archived, in amber", () => {
        const items = attention({
            apps: [app(CHECKOUT, { kind: "connected", repoFullName: "kilima-labs/checkout-api", archived: true })],
        });

        expect(items).toEqual([
            { kind: "repository-archived", app: CHECKOUT, repoFullName: "kilima-labs/checkout-api" },
        ]);
        expect(items.map(toneOf)).toEqual(["amber"]);
    });

    it("names every suspended installation, in amber", () => {
        const items = attention({
            installations: [
                { accountLogin: "kilima-labs", suspended: true },
                { accountLogin: "amani-otieno", suspended: false },
                { accountLogin: "kilima-archive", suspended: true },
            ],
        });

        expect(items).toEqual([
            { kind: "installation-suspended", accountLogin: "kilima-labs" },
            { kind: "installation-suspended", accountLogin: "kilima-archive" },
        ]);
        expect(items.map(toneOf)).toEqual(["amber", "amber"]);
    });

    it("asks someone who can connect GitHub to do so when nothing is connected", () => {
        const items = attention({ installations: [] });

        expect(items).toEqual([{ kind: "connect-github" }]);
        expect(items.map(toneOf)).toEqual(["blue"]);
    });

    it("asks to link a repository to the newest app when none of the newest has one", () => {
        expect(attention({ apps: [app(DOCS, { kind: "unlinked" }), app(CHECKOUT, { kind: "unlinked" })] })).toEqual([
            { kind: "link-repository", app: DOCS },
        ]);
    });

    it("doesn't ask for a link while one of the newest apps has one, or might", () => {
        expect(attention({ apps: [app(DOCS, { kind: "unlinked" }), app(CHECKOUT)] })).toEqual([]);
        expect(attention({ apps: [app(DOCS, { kind: "unlinked" }), app(CHECKOUT, { kind: "unknown" })] })).toEqual([]);
    });

    it("doesn't ask for a link when there are no apps", () => {
        expect(attention({ apps: [] })).toEqual([]);
    });

    it("counts pending invites and unread notifications, in blue", () => {
        const items = attention({ pendingInvites: 2, unreadNotifications: 7 });

        expect(items).toEqual([
            { kind: "pending-invites", count: 2 },
            { kind: "unread-notifications", count: 7 },
        ]);
        expect(items.map(toneOf)).toEqual(["blue", "blue"]);
    });

    it("leaves out what only an admin can act on for a viewer", () => {
        const viewer: AttentionInput<Reason> = {
            apps: [app(DOCS, { kind: "unlinked" })],
            installations: [],
            canManageInstallations: false,
            pendingInvites: undefined,
            unreadNotifications: 3,
        };

        expect(buildAttentionList(viewer).map(({ kind }) => kind)).toEqual(["link-repository", "unread-notifications"]);
    });

    it("ranks by severity whatever order the conditions arise in", () => {
        const items = attention({
            apps: [
                app(DOCS, { kind: "connected", repoFullName: "kilima-labs/docs", archived: true }),
                app(RECEIPTS, {
                    kind: "disconnected",
                    repoFullName: "kilima-labs/receipts",
                    reason: "REPOSITORY_DELETED",
                }),
            ],
            installations: [{ accountLogin: "kilima-labs", suspended: true }],
            pendingInvites: 1,
        });

        expect(items.map(({ kind }) => kind)).toEqual([
            "repository-disconnected",
            "repository-archived",
            "installation-suspended",
            "pending-invites",
        ]);
    });

    it(`shows at most ${String(MAX_ATTENTION_ITEMS)}, dropping the least severe`, () => {
        const disconnected = (ref: AppRef): RecentAppState<Reason> =>
            app(ref, { kind: "disconnected", repoFullName: `kilima-labs/${ref.appId}`, reason: "REPOSITORY_DELETED" });
        const items = attention({
            apps: [disconnected(CHECKOUT), disconnected(RECEIPTS), disconnected(DOCS)],
            installations: [
                { accountLogin: "kilima-labs", suspended: true },
                { accountLogin: "amani-otieno", suspended: true },
            ],
            pendingInvites: 4,
            unreadNotifications: 9,
        });

        expect(items).toHaveLength(MAX_ATTENTION_ITEMS);
        expect(items.map(toneOf)).toEqual(["red", "red", "red", "amber", "amber"]);
    });
});
