import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { ConfirmDialog } from "./ConfirmDialog";

function renderOpen(onConfirm = vi.fn()) {
    render(
        <ConfirmDialog
            open
            title="Delete Kilima Labs?"
            description="You can't undo this."
            confirmValue="kilima-labs"
            confirmLabel="Delete organization"
            onConfirm={onConfirm}
        />,
    );
    return {
        input: screen.getByLabelText("Type kilima-labs to confirm"),
        confirm: screen.getByRole("button", { name: "Delete organization" }),
        onConfirm,
    };
}

describe("ConfirmDialog", () => {
    it("keeps confirm disabled until the typed value matches exactly", async () => {
        const user = userEvent.setup();
        const { input, confirm } = renderOpen();

        expect(confirm).toBeDisabled();
        await user.type(input, "kilima-lab");
        expect(confirm).toBeDisabled();
        await user.type(input, "S");
        expect(confirm).toBeDisabled();
        await user.clear(input);
        await user.type(input, " kilima-labs");
        expect(confirm).toBeDisabled();
        await user.clear(input);
        await user.type(input, "kilima-labs");
        expect(confirm).toBeEnabled();
    });

    it("confirms on submit once the value matches", async () => {
        const user = userEvent.setup();
        const { input, onConfirm } = renderOpen();

        await user.type(input, "kilima-labs{Enter}");

        expect(onConfirm).toHaveBeenCalledOnce();
    });

    it("doesn't confirm on Enter before the value matches", async () => {
        const user = userEvent.setup();
        const { input, onConfirm } = renderOpen();

        await user.type(input, "kilima{Enter}");

        expect(onConfirm).not.toHaveBeenCalled();
    });
});
