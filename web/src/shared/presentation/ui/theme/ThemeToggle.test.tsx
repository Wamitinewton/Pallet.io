import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { ThemeProvider } from "../../providers/ThemeProvider";
import { ThemeToggle } from "./ThemeToggle";

describe("ThemeToggle", () => {
    it("sets data-theme on <html> and labels itself with the theme it switches to", async () => {
        const user = userEvent.setup();
        render(
            <ThemeProvider>
                <ThemeToggle />
            </ThemeProvider>,
        );

        await user.click(screen.getByRole("button", { name: "Switch to dark theme" }));
        expect(document.documentElement).toHaveAttribute("data-theme", "dark");

        await user.click(screen.getByRole("button", { name: "Switch to light theme" }));
        expect(document.documentElement).toHaveAttribute("data-theme", "light");
    });
});
