import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Input } from "../input/Input";
import { Field } from "./Field";

describe("Field", () => {
    it("labels the control", () => {
        render(
            <Field label="Work email">
                <Input type="email" />
            </Field>,
        );

        expect(screen.getByLabelText("Work email")).toHaveAttribute("type", "email");
    });

    it("describes the control with its hint", () => {
        render(
            <Field label="Password" hint="At least 12 characters.">
                <Input type="password" />
            </Field>,
        );

        const input = screen.getByLabelText("Password");
        expect(input).toHaveAccessibleDescription("At least 12 characters.");
        expect(input).not.toHaveAttribute("aria-invalid");
    });

    it("links the error through aria-describedby and marks the control invalid", () => {
        render(
            <Field label="URL" hint="Lowercase letters, numbers and hyphens." error="That URL is taken.">
                <Input />
            </Field>,
        );

        const input = screen.getByLabelText("URL");
        expect(input).toHaveAttribute("aria-invalid", "true");
        expect(input).toHaveAccessibleDescription("Lowercase letters, numbers and hyphens. That URL is taken.");
        const errorId = screen.getByText("That URL is taken.").id;
        expect(input.getAttribute("aria-describedby")?.split(" ")).toContain(errorId);
    });

    it("keeps a description the control already had", () => {
        render(
            <>
                <span id="extra">Shown on your profile.</span>
                <Field label="Name" error="Enter your name.">
                    <Input aria-describedby="extra" />
                </Field>
            </>,
        );

        expect(screen.getByLabelText("Name")).toHaveAccessibleDescription("Enter your name. Shown on your profile.");
    });
});
