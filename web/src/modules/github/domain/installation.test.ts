import { asInstallationId } from "@/shared/domain/ids";
import { describe, expect, it } from "vitest";
import { installationIdFrom, installationSettingsUrl, linkableInstallations } from "./installation";
import { AMANI_INSTALLATION, aVisibleInstallation, KILIMA_LABS_INSTALLATION } from "./testing/fixtures";

describe("installationIdFrom", () => {
    it("reads a positive integer", () => {
        expect(installationIdFrom("41000001")).toBe(41000001);
    });

    it.each([null, undefined, "", "0", "007", "-4", "4.0", "4e1", "9007199254740992"])("refuses %j", (value) => {
        expect(installationIdFrom(value)).toBeUndefined();
    });
});

describe("linkableInstallations", () => {
    it("marks what this organization already links and holds back what GitHub suspended", () => {
        const wanjiru = aVisibleInstallation({
            installationId: asInstallationId(41000003),
            accountLogin: "wanjiru-kamau",
            accountType: "User",
            suspended: true,
        });
        const amani = aVisibleInstallation({ installationId: AMANI_INSTALLATION, accountLogin: "amani-otieno" });

        expect(
            linkableInstallations(
                [aVisibleInstallation(), amani, wanjiru],
                new Set([KILIMA_LABS_INSTALLATION, wanjiru.installationId]),
            ).map(({ installation, linkability }) => [installation.accountLogin, linkability]),
        ).toEqual([
            ["kilima-labs", "linkedHere"],
            ["amani-otieno", "available"],
            ["wanjiru-kamau", "suspended"],
        ]);
    });
});

describe("installationSettingsUrl", () => {
    it("opens an organization's installation settings under the organization", () => {
        expect(
            installationSettingsUrl({
                installationId: KILIMA_LABS_INSTALLATION,
                accountLogin: "kilima-labs",
                accountType: "Organization",
            }),
        ).toBe("https://github.com/organizations/kilima-labs/settings/installations/41000001");
    });

    it("opens a personal account's installation settings under the signed-in user", () => {
        expect(
            installationSettingsUrl({
                installationId: AMANI_INSTALLATION,
                accountLogin: "amani-otieno",
                accountType: "User",
            }),
        ).toBe("https://github.com/settings/installations/41000002");
    });
});
