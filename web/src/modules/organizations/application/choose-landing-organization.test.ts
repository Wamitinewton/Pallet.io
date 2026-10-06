import { asOrgId } from "@/shared/domain/ids";
import { describe, expect, it } from "vitest";
import { anOrgSummary } from "../domain/testing/fixtures";
import { chooseLandingOrganization } from "./choose-landing-organization";

const personal = anOrgSummary({ orgId: asOrgId("org-amani"), name: "Amani Otieno", kind: "PERSONAL" });
const kilima = anOrgSummary({ orgId: asOrgId("org-kilima"), name: "Kilima Labs" });
const savanna = anOrgSummary({ orgId: asOrgId("org-savanna"), name: "Savanna Pay", myRole: "DEVELOPER" });

describe("chooseLandingOrganization", () => {
    it("lands on the last organization used while it is still mine", () => {
        expect(chooseLandingOrganization([personal, kilima, savanna], "org-savanna")).toBe("org-savanna");
        expect(chooseLandingOrganization([personal, kilima], "org-amani")).toBe("org-amani");
    });

    it("ignores a remembered organization that is no longer mine", () => {
        expect(chooseLandingOrganization([personal, kilima], "org-left-last-week")).toBe("org-kilima");
    });

    it("prefers the first team organization to the personal one", () => {
        expect(chooseLandingOrganization([personal, kilima, savanna], undefined)).toBe("org-kilima");
    });

    it("falls back to the personal organization when it is the only one", () => {
        expect(chooseLandingOrganization([personal], undefined)).toBe("org-amani");
    });

    it("has nowhere to land while the list is still empty", () => {
        expect(chooseLandingOrganization([], "org-kilima")).toBeUndefined();
    });
});
