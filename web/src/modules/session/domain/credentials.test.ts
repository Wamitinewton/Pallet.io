import { describe, expect, it } from "vitest";
import { credentialsSchema } from "./credentials";

const messages = (input: unknown): Record<string, string> => {
    const result = credentialsSchema.safeParse(input);
    return result.success
        ? {}
        : Object.fromEntries(result.error.issues.map((issue) => [String(issue.path[0]), issue.message]));
};

describe("credentialsSchema", () => {
    it("accepts an email and any non-empty password, trimming the email", () => {
        expect(credentialsSchema.parse({ email: "  ada@example.com ", password: "x" })).toEqual({
            email: "ada@example.com",
            password: "x",
        });
    });

    it("keeps the password exactly as typed", () => {
        expect(credentialsSchema.parse({ email: "ada@example.com", password: "  spaced  " }).password).toBe(
            "  spaced  ",
        );
    });

    it("requires both fields", () => {
        expect(messages({ email: " ", password: "" })).toEqual({
            email: "Enter your email address",
            password: "Enter your password",
        });
    });

    it.each(["ada", "ada@", "@example.com", `${"a".repeat(250)}@example.com`])("rejects the email %s", (email) => {
        expect(messages({ email, password: "x" })).toEqual({ email: "Enter a valid email address" });
    });
});
