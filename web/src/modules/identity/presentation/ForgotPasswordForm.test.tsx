import { manualClock } from "@/test/clock";
import { error, ok } from "@/test/msw/envelopes";
import { bffUrl, server } from "@/test/msw/server";
import { renderWithProviders, TEST_NOW } from "@/test/render";
import { act, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ForgotPasswordForm } from "./ForgotPasswordForm";

const FORGOT_URL = bffUrl("/identity/auth/password/forgot");
const REAL = "amani@kilimalabs.co";
const MADE_UP = "nobody@example.com";

const emailInput = () => screen.getByLabelText("Email");
const sendButton = () => screen.getByRole("button", { name: "Send reset link" });
const sentCard = () => screen.getByRole("heading", { name: "Check your email" }).closest("[tabindex]");
const visible = { ignore: "[role=status]" };

function replyToForgot(reply: (attempt: number) => Response | Promise<Response>) {
    const bodies: unknown[] = [];
    server.use(
        http.post(FORGOT_URL, async ({ request }) => {
            bodies.push(await request.json());
            return reply(bodies.length);
        }),
    );
    return bodies;
}

const accepted = () => ok(null, "Password reset instructions sent if the account exists", { status: 202 });

async function requestLinkFor(email: string) {
    const rendered = renderWithProviders(<ForgotPasswordForm />);
    await rendered.user.type(emailInput(), email);
    await rendered.user.click(sendButton());
    await screen.findByRole("heading", { name: "Check your email" });
    return rendered;
}

beforeEach(() => {
    vi.clearAllMocks();
});

describe("ForgotPasswordForm", () => {
    it("draws the sent state the same for every 202, naming only the address typed", async () => {
        replyToForgot(accepted);

        const real = await requestLinkFor(REAL);
        const realCard = sentCard()?.textContent;
        real.unmount();
        await requestLinkFor(MADE_UP);
        const madeUpCard = sentCard()?.textContent;

        expect(realCard).toContain(REAL);
        expect(madeUpCard).toContain(MADE_UP);
        expect(realCard?.replace(REAL, "<email>")).toBe(madeUpCard?.replace(MADE_UP, "<email>"));
        expect(madeUpCard).toContain("If an account exists for");
        expect(madeUpCard).toContain("It works once, for one hour.");
    });

    it("sends the trimmed address and moves focus to the sent state", async () => {
        const bodies = replyToForgot(accepted);
        const { user } = renderWithProviders(<ForgotPasswordForm />);

        await user.type(emailInput(), `  ${REAL} {Enter}`);

        expect(await screen.findByText(REAL)).toBeInTheDocument();
        expect(bodies).toEqual([{ email: REAL }]);
        expect(sentCard()).toHaveFocus();
    });

    it("prefills the address carried from sign-in", () => {
        renderWithProviders(<ForgotPasswordForm email={REAL} />);

        expect(emailInput()).toHaveValue(REAL);
        expect(screen.getByRole("link", { name: "Back to sign in" })).toHaveAttribute(
            "href",
            "/login?email=amani%40kilimalabs.co",
        );
    });

    it("checks the address before sending anything", async () => {
        const bodies = replyToForgot(accepted);
        const { user } = renderWithProviders(<ForgotPasswordForm />);

        await user.type(emailInput(), "amani");
        await user.click(sendButton());

        expect(await screen.findByText("Enter a valid email address")).toBeInTheDocument();
        expect(bodies).toEqual([]);
    });

    it("goes back to the form with the address kept for another try", async () => {
        replyToForgot(accepted);
        const { user } = await requestLinkFor(REAL);

        await user.click(screen.getByRole("button", { name: "Use a different email" }));

        expect(emailInput()).toHaveValue(REAL);
        expect(emailInput()).toHaveFocus();
    });

    it("puts a server field error on the email field", async () => {
        replyToForgot(() =>
            error(400, "VALIDATION_ERROR", {
                validationErrors: [{ field: "email", message: "must be a well-formed email address" }],
            }),
        );
        const { user } = renderWithProviders(<ForgotPasswordForm />);

        await user.type(emailInput(), REAL);
        await user.click(sendButton());

        expect(await screen.findByText("must be a well-formed email address")).toBeInTheDocument();
        expect(screen.queryByRole("heading", { name: "Check your email" })).not.toBeInTheDocument();
    });

    it("shows the limited callout on a 429 and holds the button until it ends", async () => {
        vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
        const clock = manualClock(TEST_NOW);
        replyToForgot(() => error(429, "TOO_MANY_REQUESTS", { meta: { retryAfter: 20 } }));
        const { user } = renderWithProviders(<ForgotPasswordForm />, { clock });

        await user.type(emailInput(), REAL);
        await user.click(sendButton());

        expect(
            await screen.findByText("Too many attempts. Try again in 20 seconds.", { selector: "strong" }),
        ).toBeInTheDocument();
        expect(sendButton()).toBeDisabled();

        act(() => {
            clock.advance(20_000);
            vi.advanceTimersByTime(250);
        });
        await waitFor(() => {
            expect(sendButton()).toBeEnabled();
        });
    });

    it("offers a retry after a network failure", async () => {
        const bodies = replyToForgot((attempt) => (attempt === 1 ? HttpResponse.error() : accepted()));
        const { user } = renderWithProviders(<ForgotPasswordForm />);

        await user.type(emailInput(), REAL);
        await user.click(sendButton());
        await user.click(await screen.findByRole("button", { name: "Try again" }));

        expect(await screen.findByText(REAL, visible)).toBeInTheDocument();
        expect(bodies).toEqual([{ email: REAL }, { email: REAL }]);
    });
});
