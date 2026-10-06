import { SessionExpiryHandler } from "@/modules/session";
import { error, ok } from "@/test/msw/envelopes";
import { bffUrl, server } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";
import { screen, waitFor } from "@testing-library/react";
import { http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { PasswordPanel } from "./PasswordPanel";
import { identityKeys } from "./queries";

const router = vi.hoisted(() => ({ replace: vi.fn(), refresh: vi.fn(), push: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router }));

const PASSWORD_URL = bffUrl("/identity/users/me/password");
const CURRENT = "old-river-crossing";
const NEXT = "mango-season-in-kisumu";

const currentInput = () => screen.getByLabelText("Current password");
const newInput = () => screen.getByLabelText("New password");
const confirmationInput = () => screen.getByLabelText("Type it again");
const updateButton = () => screen.getByRole("button", { name: "Update password" });

function replyToChange(reply: () => Response) {
    const bodies: unknown[] = [];
    server.use(
        http.post(PASSWORD_URL, async ({ request }) => {
            bodies.push(await request.json());
            return reply();
        }),
    );
    return bodies;
}

function renderPanel() {
    return renderWithProviders(
        <>
            <SessionExpiryHandler />
            <PasswordPanel email="amani@kilimalabs.co" />
        </>,
    );
}

async function change(user: ReturnType<typeof renderPanel>["user"], current: string, next: string, again = next) {
    await user.type(currentInput(), current);
    await user.type(newInput(), next);
    await user.type(confirmationInput(), again);
    await user.click(updateButton());
}

beforeEach(() => {
    vi.clearAllMocks();
});

describe("PasswordPanel", () => {
    it("puts a wrong current password on its field and keeps the person signed in", async () => {
        replyToChange(() => error(401, "INVALID_CREDENTIALS", { message: "Invalid credentials." }));
        const { user } = renderPanel();

        await change(user, "not-my-password", NEXT);

        expect(await screen.findByText("That isn't your current password.")).toBeInTheDocument();
        expect(currentInput()).toHaveAttribute("aria-invalid", "true");
        expect(currentInput()).toHaveFocus();
        expect(newInput()).toHaveValue(NEXT);
        expect(router.replace).not.toHaveBeenCalled();
        expect(screen.queryByText(/session has ended/i)).not.toBeInTheDocument();
    });

    it("blocks a mismatched confirmation without sending anything", async () => {
        const bodies = replyToChange(() => ok(null, "Password changed"));
        const { user } = renderPanel();

        await change(user, CURRENT, NEXT, `${NEXT}!`);

        expect(await screen.findByText("These don't match yet.")).toBeInTheDocument();
        expect(confirmationInput()).toHaveAttribute("aria-invalid", "true");
        expect(bodies).toEqual([]);
    });

    it("applies the password policy to the new password before sending anything", async () => {
        const bodies = replyToChange(() => ok(null, "Password changed"));
        const { user } = renderPanel();

        await change(user, CURRENT, "short");

        expect(await screen.findByText("Use at least 12 characters")).toBeInTheDocument();
        expect(bodies).toEqual([]);
    });

    it("changes the password, clears the form and has the session list read again", async () => {
        const bodies = replyToChange(() => ok(null, "Password changed"));
        const { user, queryClient } = renderPanel();
        queryClient.setQueryData(identityKeys.sessions(), []);

        await change(user, CURRENT, NEXT);

        expect(await screen.findByText("Password changed")).toBeInTheDocument();
        expect(bodies).toEqual([{ currentPassword: CURRENT, newPassword: NEXT }]);
        await waitFor(() => {
            expect(currentInput()).toHaveValue("");
        });
        expect(newInput()).toHaveValue("");
        expect(confirmationInput()).toHaveValue("");
        expect(queryClient.getQueryState(identityKeys.sessions())?.isInvalidated).toBe(true);
    });

    it("puts a server field error on the new password", async () => {
        replyToChange(() =>
            error(400, "VALIDATION_ERROR", {
                validationErrors: [{ field: "newPassword", message: "must be at least 12 characters" }],
            }),
        );
        const { user } = renderPanel();

        await change(user, CURRENT, NEXT);

        expect(await screen.findByText("must be at least 12 characters")).toBeInTheDocument();
    });

    it("offers the reset flow for a forgotten current password", () => {
        renderPanel();

        expect(screen.getByRole("link", { name: "Forgot your current password?" })).toHaveAttribute(
            "href",
            "/forgot-password?email=amani%40kilimalabs.co",
        );
    });
});
