import { ApiError, NetworkError } from "@/shared/domain/errors";
import { describe, expect, it } from "vitest";
import { isOrganizationUnavailable, isSameName, newOrganizationSchema, organizationNameSchema } from "./organization";

const apiError = (status: number, code: string) => new ApiError(status, code, "message", [], {}, undefined);

describe("isOrganizationUnavailable", () => {
    it.each([
        [apiError(404, "ORG_NOT_FOUND"), true],
        [apiError(403, "NOT_A_MEMBER"), true],
        [apiError(403, "INSUFFICIENT_ROLE"), false],
        [apiError(503, "SERVICE_UNAVAILABLE"), false],
        [new NetworkError(), false],
    ])("%o is %s", (error, unavailable) => {
        expect(isOrganizationUnavailable(error)).toBe(unavailable);
    });
});

describe("organizationNameSchema", () => {
    it("trims the name", () => {
        expect(organizationNameSchema.parse("  Kilima Labs ")).toBe("Kilima Labs");
    });

    it.each([
        ["a blank name", "   ", "Enter a name for the organization"],
        ["a name over 255 characters", "a".repeat(256), "Keep the name to 255 characters or fewer"],
        ["a control character", "Kilima\u0007Labs", "Remove the control characters from the name"],
        ["DEL", "Kilima\u007fLabs", "Remove the control characters from the name"],
    ])("refuses %s", (_, name, message) => {
        expect(organizationNameSchema.safeParse(name).error?.issues[0]?.message).toBe(message);
    });

    it("accepts 255 characters and characters beyond ASCII", () => {
        expect(organizationNameSchema.safeParse("a".repeat(255)).success).toBe(true);
        expect(organizationNameSchema.safeParse("Café Nyota 日本").success).toBe(true);
    });
});

describe("newOrganizationSchema", () => {
    it("leaves an empty slug out so the backend derives it", () => {
        expect(newOrganizationSchema.parse({ name: "Savanna Pay", slug: "  " })).toEqual({
            name: "Savanna Pay",
            slug: undefined,
        });
    });

    it.each([
        ["savanna-pay", true],
        ["a".repeat(63), true],
        ["a".repeat(64), false],
        ["Savanna", false],
        ["-savanna", false],
        ["savanna--pay", false],
    ])("%s is a valid slug: %s", (slug, valid) => {
        expect(newOrganizationSchema.safeParse({ name: "Savanna Pay", slug }).success).toBe(valid);
    });
});

describe("isSameName", () => {
    it("ignores surrounding blanks", () => {
        expect(isSameName({ name: "Kilima Labs" }, " Kilima Labs ")).toBe(true);
        expect(isSameName({ name: "Kilima Labs" }, "Kilima")).toBe(false);
    });
});
