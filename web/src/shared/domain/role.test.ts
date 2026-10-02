import { describe, expect, it } from "vitest";
import orgTeamSpec from "../../../openapi/org-team.json";
import { atLeast, isRole, ROLES, type Role } from "./role";

describe("role", () => {
    it("orders roles exactly as the backend's Role enum", () => {
        expect(ROLES).toEqual(orgTeamSpec.components.schemas.MemberDto.properties.role.enum);
    });

    const expected: Record<Role, readonly Role[]> = {
        VIEWER: ["VIEWER"],
        DEVELOPER: ["VIEWER", "DEVELOPER"],
        ADMIN: ["VIEWER", "DEVELOPER", "ADMIN"],
        OWNER: ["VIEWER", "DEVELOPER", "ADMIN", "OWNER"],
    };

    it.each(ROLES.flatMap((role) => ROLES.map((minimum) => [role, minimum] as const)))(
        "atLeast(%s, %s)",
        (role, minimum) => {
            expect(atLeast(role, minimum)).toBe(expected[role].includes(minimum));
        },
    );

    it("recognises only backend role names", () => {
        expect(isRole("ADMIN")).toBe(true);
        expect(isRole("admin")).toBe(false);
        expect(isRole("SUPERUSER")).toBe(false);
    });
});
