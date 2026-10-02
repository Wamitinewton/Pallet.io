import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { Button } from "../button/Button";
import { Toaster, useToast } from "./Toaster";

function Save() {
    const toast = useToast();
    return (
        <Button
            onClick={() => {
                toast.success("Organization created");
            }}
        >
            Save
        </Button>
    );
}

describe("Toaster", () => {
    it("announces a toast politely", async () => {
        const user = userEvent.setup();
        render(
            <Toaster>
                <Save />
            </Toaster>,
        );

        await user.click(screen.getByRole("button", { name: "Save" }));

        const status = screen.getByRole("status");
        expect(status).toHaveAttribute("aria-live", "polite");
        await waitFor(() => {
            expect(status).toHaveTextContent("Organization created");
        });
    });

    it("refuses to be used outside the provider", () => {
        expect(() => render(<Save />)).toThrow("useToast must be used inside <Toaster>");
    });
});
