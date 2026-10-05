import { manualClock } from "@/test/clock";
import { error, ok } from "@/test/msw/envelopes";
import { bffUrl, server } from "@/test/msw/server";
import { renderWithProviders, TEST_NOW } from "@/test/render";
import { act, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ResetPasswordForm } from "./ResetPasswordForm";

const router = vi.hoisted(() => ({ replace: vi.fn(), refresh: vi.fn(), push: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router }));

const RESET_URL = bffUrl("/identity/auth/password/reset");
const TOKEN = "q3Jx0f2Zr9VbL1mN8sT4uW6yA7cE5gH0iK2oP_-dQ1s";
const PASSWORD = "mango-season-in-kisumu";

const passwordInput = () => screen.getByLabelText("New password");
const confirmationInput = () => screen.getByLabelText("Type it again");
const saveButton = () => screen.getByRole("button", { name: "Save new password" });
const expiredHeading = () => screen.queryByRole("heading", { name: "This link has expired" });

function replyToReset(reply: (attempt: number) => Response | Promise<Response>) {
    const bodies: unknown[] = [];
    server.use(
        http.post(RESET_URL, async ({ request }) => {
            bodies.push(await request.json());
            return reply(bodies.length);
        }),
    );
    return bodies;
}

const updated = () => ok(null, "Password reset");

async function choose(user: ReturnType<typeof renderWithProviders>["user"], password: string, confirmation: string) {
    await user.type(passwordInput(), password);
    await user.type(confirmationInput(), confirmation);
    await user.click(saveButton());
}

beforeEach(() => {
    vi.clearAllMocks();
    window.history.replaceState(null, "", `/reset-password?token=${TOKEN}`);
});

describe("ResetPasswordForm", () => {
    it("takes the token out of the address bar on mount and keeps it for the request", async () => {
        const bodies = replyToReset(updated);
        const { user } = renderWithProviders(<ResetPasswordForm token={TOKEN} />);

        expect(window.location.pathname).toBe("/reset-password");
        expect(window.location.search).toBe("");
        expect(window.location.href).not.toContain(TOKEN);

        await choose(user, PASSWORD, PASSWORD);

        await waitFor(() => {
            expect(bodies).toEqual([{ token: TOKEN, newPassword: PASSWORD }]);
        });
    });

    it("navigates to sign-in with reset=1 once the password is updated", async () => {
        replyToReset(updated);
        const { user } = renderWithProviders(<ResetPasswordForm token={TOKEN} />);

        await choose(user, PASSWORD, PASSWORD);

        await waitFor(() => {
            expect(router.replace).toHaveBeenCalledWith("/login?reset=1");
        });
    });

    it("shows a mismatch on the confirmation and sends nothing", async () => {
        const bodies = replyToReset(updated);
        const { user } = renderWithProviders(<ResetPasswordForm token={TOKEN} />);

        await choose(user, PASSWORD, "mango-season-in-kisum");

        expect(await screen.findByText("These don't match yet.")).toBeInTheDocument();
        expect(confirmationInput()).toHaveAttribute("aria-invalid", "true");
        expect(bodies).toEqual([]);
    });

    it("clears the mismatch once the password is changed to match", async () => {
        replyToReset(updated);
        const { user } = renderWithProviders(<ResetPasswordForm token={TOKEN} />);

        await choose(user, "mango-season-in-kisum", PASSWORD);
        await screen.findByText("These don't match yet.");
        await user.type(passwordInput(), "u");

        await waitFor(() => {
            expect(screen.queryByText("These don't match yet.")).not.toBeInTheDocument();
        });
    });

    it("applies the password policy before sending anything", async () => {
        const bodies = replyToReset(updated);
        const { user } = renderWithProviders(<ResetPasswordForm token={TOKEN} />);

        await choose(user, "short", "short");

        expect(await screen.findByText("Use at least 12 characters")).toBeInTheDocument();
        expect(bodies).toEqual([]);
    });

    it("rates the new password as it is typed", async () => {
        const { user } = renderWithProviders(<ResetPasswordForm token={TOKEN} />);

        await user.type(passwordInput(), "Correct-horse-battery-9");

        expect(screen.getByRole("meter")).toHaveAttribute("aria-valuetext", "Strong");
    });

    it("turns INVALID_TOKEN into the expired state and focuses it", async () => {
        replyToReset(() => error(400, "INVALID_TOKEN", { message: "This link is invalid or has expired." }));
        const { user } = renderWithProviders(<ResetPasswordForm token={TOKEN} />);

        await choose(user, PASSWORD, PASSWORD);

        expect(await screen.findByRole("heading", { name: "This link has expired" })).toBeInTheDocument();
        expect(screen.getByRole("heading", { name: "This link has expired" }).closest("[tabindex]")).toHaveFocus();
        expect(screen.getByRole("link", { name: "Send a new link" })).toHaveAttribute("href", "/forgot-password");
        expect(router.replace).not.toHaveBeenCalled();
    });

    it("shows the expired state directly when the link carried no token", () => {
        renderWithProviders(<ResetPasswordForm />);

        expect(expiredHeading()).toBeInTheDocument();
        expect(screen.queryByLabelText("New password")).not.toBeInTheDocument();
        expect(screen.getByRole("link", { name: "Back to sign in" })).toHaveAttribute("href", "/login");
    });

    it("puts a server field error on the new password", async () => {
        replyToReset(() =>
            error(400, "VALIDATION_ERROR", {
                validationErrors: [{ field: "newPassword", message: "must be at least 12 characters" }],
            }),
        );
        const { user } = renderWithProviders(<ResetPasswordForm token={TOKEN} />);

        await choose(user, PASSWORD, PASSWORD);

        expect(await screen.findByText("must be at least 12 characters")).toBeInTheDocument();
        expect(expiredHeading()).not.toBeInTheDocument();
    });

    it("holds the button through a 429", async () => {
        vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
        const clock = manualClock(TEST_NOW);
        replyToReset(() => error(429, "TOO_MANY_REQUESTS", { meta: { retryAfter: 10 } }));
        const { user } = renderWithProviders(<ResetPasswordForm token={TOKEN} />, { clock });

        await choose(user, PASSWORD, PASSWORD);

        expect(
            await screen.findByText("Too many attempts. Try again in 10 seconds.", { selector: "strong" }),
        ).toBeInTheDocument();
        expect(saveButton()).toBeDisabled();

        act(() => {
            clock.advance(10_000);
            vi.advanceTimersByTime(250);
        });
        await waitFor(() => {
            expect(saveButton()).toBeEnabled();
        });
    });

    it("retries with the same token and password after a network failure", async () => {
        const bodies = replyToReset((attempt) => (attempt === 1 ? HttpResponse.error() : updated()));
        const { user } = renderWithProviders(<ResetPasswordForm token={TOKEN} />);

        await choose(user, PASSWORD, PASSWORD);
        await user.click(await screen.findByRole("button", { name: "Try again" }));

        await waitFor(() => {
            expect(router.replace).toHaveBeenCalledWith("/login?reset=1");
        });
        expect(bodies).toEqual([
            { token: TOKEN, newPassword: PASSWORD },
            { token: TOKEN, newPassword: PASSWORD },
        ]);
    });
});
