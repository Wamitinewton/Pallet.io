import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Button } from "../button/Button";
import { Menu, MenuContent, MenuItem, MenuTrigger } from "./Menu";

function renderMenu() {
    const onSelect = vi.fn<(item: string) => void>();
    render(
        <Menu>
            <MenuTrigger>
                <Button>Actions</Button>
            </MenuTrigger>
            <MenuContent>
                <MenuItem
                    onSelect={() => {
                        onSelect("role");
                    }}
                >
                    Change role
                </MenuItem>
                <MenuItem
                    onSelect={() => {
                        onSelect("owner");
                    }}
                >
                    Make owner
                </MenuItem>
                <MenuItem
                    tone="danger"
                    onSelect={() => {
                        onSelect("remove");
                    }}
                >
                    Remove from organization
                </MenuItem>
            </MenuContent>
        </Menu>,
    );
    return { trigger: screen.getByRole("button", { name: "Actions" }), onSelect };
}

describe("Menu", () => {
    it("moves with the arrow keys and selects with Enter", async () => {
        const user = userEvent.setup();
        const { trigger, onSelect } = renderMenu();

        trigger.focus();
        await user.keyboard("{Enter}");
        expect(screen.getByRole("menuitem", { name: "Change role" })).toHaveFocus();

        await user.keyboard("{ArrowDown}");
        expect(screen.getByRole("menuitem", { name: "Make owner" })).toHaveFocus();
        await user.keyboard("{ArrowDown}");
        expect(screen.getByRole("menuitem", { name: "Remove from organization" })).toHaveFocus();
        await user.keyboard("{ArrowUp}");

        await user.keyboard("{Enter}");
        expect(onSelect).toHaveBeenCalledExactlyOnceWith("owner");
        expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    });

    it("closes on Escape and returns focus to the trigger", async () => {
        const user = userEvent.setup();
        const { trigger, onSelect } = renderMenu();

        trigger.focus();
        await user.keyboard("{Enter}");
        expect(screen.getByRole("menu")).toBeInTheDocument();

        await user.keyboard("{Escape}");
        expect(screen.queryByRole("menu")).not.toBeInTheDocument();
        expect(trigger).toHaveFocus();
        expect(onSelect).not.toHaveBeenCalled();
    });
});
