import { describe, expect, it } from "vitest";
import { linkHealth, type Remedy } from "./link-health";
import type { DisconnectReason } from "./repo-link";
import { aDisconnectedLink, aRepoLink } from "./testing/fixtures";

describe("linkHealth", () => {
    it("is unlinked without a link", () => {
        expect(linkHealth(null)).toEqual({ kind: "unlinked" });
    });

    it("is connected for an active link without warnings", () => {
        const link = aRepoLink();

        expect(linkHealth(link)).toEqual({ kind: "connected", link });
    });

    it("is connected with warnings for an archived repository", () => {
        const link = aRepoLink({ warnings: ["REPOSITORY_ARCHIVED"] });

        expect(linkHealth(link)).toEqual({ kind: "connected-with-warnings", link, warnings: ["REPOSITORY_ARCHIVED"] });
    });

    it("is unlinked once an admin unlinks on purpose, naming what was linked", () => {
        expect(linkHealth(aDisconnectedLink("UNLINKED_BY_USER"))).toEqual({
            kind: "unlinked",
            previous: { repoFullName: "kilima-labs/checkout-api", unlinkedAt: "2026-01-14T09:12:00Z" },
        });
    });

    it.each<[DisconnectReason, Remedy]>([
        ["INSTALLATION_UNLINKED", "reconnect-installation"],
        ["INSTALLATION_DELETED", "reconnect-installation"],
        ["REPOSITORY_ACCESS_REMOVED", "restore-access"],
        ["REPOSITORY_DELETED", "relink"],
        ["VERIFIER_ACCESS_LOST", "relink"],
    ])("is disconnected by %s, mended by %s", (reason, remedy) => {
        const link = aDisconnectedLink(reason);

        expect(linkHealth(link)).toEqual({
            kind: "disconnected",
            link,
            reason,
            disconnectedAt: "2026-01-14T09:12:00Z",
            remedy,
        });
    });

    it.each<DisconnectReason>(["APP_DELETED", "ORG_DELETED"])(
        "reads a link disconnected by %s as no link, since only a deleted page could show it",
        (reason) => {
            expect(linkHealth(aDisconnectedLink(reason))).toEqual({ kind: "unlinked" });
        },
    );
});
