import { asInstallationId, asOrgId } from "@/shared/domain/ids";
import { describe, expect, it } from "vitest";
import {
    authorizeIntent,
    backPath,
    installIntent,
    pendingLinkOf,
    readReturnIntent,
    RETURN_INTENT_TTL_MS,
    returnDestination,
} from "./return-intent";

const startedAt = new Date("2026-01-15T12:00:00Z");
const later = (millis: number) => new Date(startedAt.getTime() + millis);
const roundTrip = (intent: unknown) => JSON.parse(JSON.stringify(intent)) as unknown;
const orgId = asOrgId("org-kilima");

describe("readReturnIntent", () => {
    it("reads back an install intent stored as JSON", () => {
        const intent = installIntent(startedAt, orgId, "/orgs/org-kilima/github");

        expect(readReturnIntent(roundTrip(intent), later(60_000))).toEqual(intent);
    });

    it("reads back an authorize intent waiting to link an installation", () => {
        const intent = authorizeIntent(startedAt, "/orgs/org-kilima/github", {
            orgId,
            installationId: asInstallationId(41000001),
        });

        expect(readReturnIntent(roundTrip(intent), later(1))).toEqual(intent);
        expect(pendingLinkOf(intent)).toEqual({ orgId, installationId: 41000001 });
    });

    it("trusts an intent up to fifteen minutes old and not a moment longer", () => {
        const intent = roundTrip(installIntent(startedAt, orgId, "/orgs/org-kilima/github"));

        expect(readReturnIntent(intent, later(RETURN_INTENT_TTL_MS))).toBeDefined();
        expect(readReturnIntent(intent, later(RETURN_INTENT_TTL_MS + 1))).toBeUndefined();
    });

    it("refuses an intent from the future", () => {
        expect(
            readReturnIntent(roundTrip(installIntent(startedAt, orgId, "/orgs/x/github")), later(-1)),
        ).toBeUndefined();
    });

    it.each(["https://evil.example/orgs", "//evil.example", "/bff/git-integration/github/session", "orgs", "/\\evil"])(
        "refuses the unsafe returnTo %j",
        (returnTo) => {
            expect(
                readReturnIntent({ kind: "install", orgId, returnTo, startedAt: startedAt.toISOString() }, later(1)),
            ).toBeUndefined();
        },
    );

    it.each([
        ["nothing", undefined],
        ["not JSON-shaped", "install"],
        ["an unknown kind", { kind: "deploy", returnTo: "/orgs", startedAt: startedAt.toISOString() }],
        [
            "an install without an organization",
            { kind: "install", returnTo: "/orgs", startedAt: startedAt.toISOString() },
        ],
        [
            "a pending installation without an organization",
            { kind: "authorize", returnTo: "/orgs", startedAt: startedAt.toISOString(), installationId: 1 },
        ],
        ["a bad timestamp", { kind: "authorize", returnTo: "/orgs", startedAt: "yesterday" }],
    ])("reads %s as no intent", (_, stored) => {
        expect(readReturnIntent(stored, later(1))).toBeUndefined();
    });
});

describe("intents", () => {
    it("never records an unsafe returnTo", () => {
        expect(authorizeIntent(startedAt, "https://evil.example").returnTo).toBe("/orgs");
    });

    it("has nothing pending for a plain authorization", () => {
        expect(pendingLinkOf(authorizeIntent(startedAt, "/orgs/org-kilima/github"))).toBeUndefined();
    });
});

describe("returnDestination", () => {
    it("adds the flag beside whatever the page already carried", () => {
        expect(
            returnDestination(authorizeIntent(startedAt, "/orgs/org-kilima/github?connect=existing"), "connected"),
        ).toBe("/orgs/org-kilima/github?connect=existing&github=connected");
    });

    it("replaces a flag left from an earlier round trip", () => {
        expect(
            returnDestination(installIntent(startedAt, orgId, "/orgs/org-kilima/github?github=connected"), "installed"),
        ).toBe("/orgs/org-kilima/github?github=installed");
    });
});

describe("backPath", () => {
    it("goes back where the round trip started, or to the dashboard without an intent", () => {
        expect(backPath(installIntent(startedAt, orgId, "/orgs/org-kilima/github"))).toBe("/orgs/org-kilima/github");
        expect(backPath(undefined)).toBe("/orgs");
    });
});
