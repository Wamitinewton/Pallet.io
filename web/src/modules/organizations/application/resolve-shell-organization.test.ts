import { asOrgId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import { describe, expect, it } from "vitest";
import type { OrgSummary } from "../domain/organization";
import { anOrgSummary } from "../domain/testing/fixtures";
import { resolveShellOrganization } from "./resolve-shell-organization";

const landing = asOrgId("org-landing");

function pageOf(items: readonly OrgSummary[], isLast = true): Page<OrgSummary> {
    return { items, page: 0, size: 100, totalItems: items.length, totalPages: 1, isFirst: true, isLast };
}

describe("resolveShellOrganization", () => {
    it("follows the URL when the organization is mine", () => {
        expect(resolveShellOrganization("org-kilima", pageOf([anOrgSummary()]), landing)).toBe("org-kilima");
    });

    it("falls back when my whole list shows the URL's organization isn't mine", () => {
        expect(resolveShellOrganization("org-elsewhere", pageOf([anOrgSummary()]), landing)).toBe(landing);
    });

    it("trusts the URL while the list is unknown or only partly loaded", () => {
        expect(resolveShellOrganization("org-elsewhere", undefined, landing)).toBe("org-elsewhere");
        expect(resolveShellOrganization("org-elsewhere", pageOf([anOrgSummary()], false), landing)).toBe(
            "org-elsewhere",
        );
    });

    it("uses the fallback on pages outside any organization", () => {
        expect(resolveShellOrganization(undefined, pageOf([anOrgSummary()]), landing)).toBe(landing);
        expect(resolveShellOrganization(undefined, pageOf([]), undefined)).toBeUndefined();
    });
});
