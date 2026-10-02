import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Button } from "./Button";

describe("Button", () => {
    it("is a non-submitting button by default", () => {
        render(<Button>Save</Button>);

        expect(screen.getByRole("button", { name: "Save" })).toHaveAttribute("type", "button");
    });

    it("marks itself busy and ignores clicks while loading", async () => {
        const onClick = vi.fn();
        render(
            <Button loading onClick={onClick}>
                Save
            </Button>,
        );
        const button = screen.getByRole("button", { name: "Save" });

        expect(button).toHaveAttribute("aria-busy", "true");
        expect(button).toHaveAttribute("aria-disabled", "true");
        await userEvent.click(button, { pointerEventsCheck: 0 });
        expect(onClick).not.toHaveBeenCalled();
    });

    it("keeps its label in the layout while loading so its width doesn't change", () => {
        render(<Button loading>Create organization</Button>);

        expect(screen.getByText("Create organization")).toBeInTheDocument();
    });

    it("renders its child link with button styling and no nested button", () => {
        render(
            <Button asChild variant="primary">
                <a href="/apps/new">New app</a>
            </Button>,
        );
        const link = screen.getByRole("link", { name: "New app" });

        expect(link).toHaveAttribute("href", "/apps/new");
        expect(link.className).not.toBe("");
        expect(screen.queryByRole("button")).not.toBeInTheDocument();
    });
});
