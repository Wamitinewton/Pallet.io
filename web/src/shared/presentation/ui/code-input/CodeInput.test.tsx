import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Field } from "../field/Field";
import { CodeInput } from "./CodeInput";

const cell = (position: number) => screen.getByRole("textbox", { name: `Character ${String(position)} of 8` });

function renderCode() {
    const onValueChange = vi.fn<(value: string) => void>();
    const onComplete = vi.fn<(value: string) => void>();
    render(
        <Field label="Verification code" hint="You can paste the whole code.">
            <CodeInput onValueChange={onValueChange} onComplete={onComplete} />
        </Field>,
    );
    const cells = screen.getAllByRole("textbox");
    return { cells, onValueChange, onComplete };
}

describe("CodeInput", () => {
    it("is one labelled group", () => {
        renderCode();

        expect(screen.getByRole("group", { name: "Verification code" })).toBeInTheDocument();
        const label = screen.getByText("Verification code");
        expect(label).toHaveAttribute("for", screen.getAllByRole("textbox")[0]?.id);
    });

    it("fills all eight cells from a pasted code, upper-cased", async () => {
        const user = userEvent.setup();
        const { cells, onComplete } = renderCode();

        await user.click(cell(1));
        await user.paste("ab12cd34");

        expect(cells.map((input) => (input as HTMLInputElement).value).join("")).toBe("AB12CD34");
        expect(onComplete).toHaveBeenCalledWith("AB12CD34");
    });

    it("drops separators and spaces from a pasted code", async () => {
        const user = userEvent.setup();
        const { onValueChange } = renderCode();

        await user.click(cell(1));
        await user.paste(" ab12-cd34 ");

        expect(onValueChange).toHaveBeenLastCalledWith("AB12CD34");
    });

    it("moves focus forward per character and back on Backspace", async () => {
        const user = userEvent.setup();
        const { cells, onValueChange } = renderCode();

        await user.click(cell(1));
        await user.keyboard("k7");
        expect(cells[2]).toHaveFocus();
        expect(onValueChange).toHaveBeenLastCalledWith("K7");

        await user.keyboard("{Backspace}");
        expect(cells[1]).toHaveFocus();
        expect(cells[1]).toHaveValue("");
        expect(onValueChange).toHaveBeenLastCalledWith("K");

        await user.keyboard("{Backspace}");
        expect(cells[0]).toHaveFocus();
        expect(cells[0]).toHaveValue("");
    });

    it("ignores characters that can't be in a code", async () => {
        const user = userEvent.setup();
        const { cells } = renderCode();

        await user.click(cell(1));
        await user.keyboard("-");

        expect(cells[0]).toHaveValue("");
        expect(cells[0]).toHaveFocus();
    });
});
