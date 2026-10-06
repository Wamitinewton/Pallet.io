import { describe, expect, it } from "vitest";
import orgTeamSpec from "../../../openapi/org-team.json";
import { ORG_KINDS } from "./org-kind";

describe("org kind", () => {
    it("matches the backend's OrgKind enum", () => {
        expect(ORG_KINDS).toEqual(orgTeamSpec.components.schemas.OrgSummaryDto.properties.kind.enum);
    });
});
