import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { Button } from "../button/Button";
import { Field } from "../field/Field";
import { Input } from "../input/Input";
import { Dialog, DialogBody } from "./Dialog";

function NewOrganization() {
    return (
        <Dialog
            title="New organization"
            description="Organizations hold apps, members and teams."
            trigger={<Button>New organization</Button>}
        >
            <DialogBody>
                <Field label="Name">
                    <Input />
                </Field>
            </DialogBody>
        </Dialog>
    );
}

describe("Dialog", () => {
    it("is labelled by its title and described by its description", async () => {
        const user = userEvent.setup();
        render(<NewOrganization />);

        await user.click(screen.getByRole("button", { name: "New organization" }));

        const dialog = screen.getByRole("dialog", { name: "New organization" });
        expect(dialog).toHaveAccessibleDescription("Organizations hold apps, members and teams.");
    });

    it("moves focus in on open, closes on Escape and returns focus to the trigger", async () => {
        const user = userEvent.setup();
        render(<NewOrganization />);
        const trigger = screen.getByRole("button", { name: "New organization" });

        await user.click(trigger);
        const dialog = screen.getByRole("dialog");
        expect(dialog).toContainElement(document.activeElement as HTMLElement);

        await user.keyboard("{Escape}");
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        expect(trigger).toHaveFocus();
    });

    it("closes from its close button", async () => {
        const user = userEvent.setup();
        render(<NewOrganization />);

        await user.click(screen.getByRole("button", { name: "New organization" }));
        await user.click(screen.getByRole("button", { name: "Close" }));

        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
});
