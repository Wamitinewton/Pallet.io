import { describe, expect, it } from "vitest";
import { emailSchema, parseEmail } from "./email";

describe("emailSchema", () => {
    it("trims the address", () => {
        expect(emailSchema.parse("  ada@example.com ")).toBe("ada@example.com");
    });

    it.each(["ada", "ada@", "@example.com", `${"a".repeat(250)}@example.com`])("rejects %s", (email) => {
        expect(emailSchema.safeParse(email).success).toBe(false);
    });
});

describe("parseEmail", () => {
    it.each([
        ["ada@example.com", "ada@example.com"],
        [" ada@example.com ", "ada@example.com"],
        ["<script>@x", undefined],
        [["ada@example.com"], undefined],
        [undefined, undefined],
        ["", undefined],
    ])("reads %j as %s", (value, email) => {
        expect(parseEmail(value)).toBe(email);
    });
});
