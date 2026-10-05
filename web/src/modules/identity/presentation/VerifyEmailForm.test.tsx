import { manualClock } from "@/test/clock";
import { error, ok } from "@/test/msw/envelopes";
import { bffUrl, server } from "@/test/msw/server";
import { renderWithProviders, TEST_NOW } from "@/test/render";
import { act, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { VerifyEmailForm } from "./VerifyEmailForm";

const router = vi.hoisted(() => ({ replace: vi.fn(), refresh: vi.fn(), push: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router }));

const EMAIL = "amani@kilimalabs.co";
const VERIFY_URL = bffUrl("/identity/auth/email/verify");
const RESEND_URL = bffUrl("/identity/auth/email/resend-verification");

const cells = () => screen.getAllByRole("textbox", { name: /^Character \d of 8$/ });
const firstCell = () => screen.getByRole("textbox", { name: "Character 1 of 8" });
const confirmButton = () => screen.getByRole("button", { name: "Confirm email" });
const resendButton = () => screen.getByRole("button", { name: /Send a new code/ });
const visible = { ignore: "[role=status]" };

function replyTo(url: string, reply: (attempt: number) => Response | Promise<Response>) {
    const bodies: unknown[] = [];
    server.use(
        http.post(url, async ({ request }) => {
            bodies.push(await request.json());
            return reply(bodies.length);
        }),
    );
    return bodies;
}

const verified = () => ok(null, "Email verified");
const resent = () => ok(null, "Verification code sent if the account exists", { status: 202 });

beforeEach(() => {
    vi.clearAllMocks();
});

describe("VerifyEmailForm", () => {
    it("submits a pasted code and hands over to sign-in with the email", async () => {
        const bodies = replyTo(VERIFY_URL, verified);
        const { user } = renderWithProviders(<VerifyEmailForm email={EMAIL} />);

        expect(screen.getByText(EMAIL)).toBeInTheDocument();
        await user.click(firstCell());
        await user.paste("k7qm-3wzp");

        await waitFor(() => {
            expect(router.replace).toHaveBeenCalledWith("/login?verified=1&email=amani%40kilimalabs.co");
        });
        expect(bodies).toEqual([{ email: EMAIL, code: "K7QM3WZP" }]);
    });

    it("submits once the eighth character is typed", async () => {
        const bodies = replyTo(VERIFY_URL, verified);
        const { user } = renderWithProviders(<VerifyEmailForm email={EMAIL} />);

        await user.click(firstCell());
        await user.keyboard("K7QM3WZ");
        await user.keyboard("P");

        await waitFor(() => {
            expect(bodies).toHaveLength(1);
        });
    });

    it("asks for every character before sending anything", async () => {
        const bodies = replyTo(VERIFY_URL, verified);
        const { user } = renderWithProviders(<VerifyEmailForm email={EMAIL} />);

        await user.click(firstCell());
        await user.keyboard("K7Q{Enter}");

        expect(await screen.findByText("Enter all 8 characters", visible)).toBeInTheDocument();
        expect(bodies).toEqual([]);
    });

    it("clears the cells and refocuses the first on INVALID_TOKEN, offering a new code", async () => {
        replyTo(VERIFY_URL, () => error(400, "INVALID_TOKEN", { message: "This link is invalid or has expired." }));
        const { user } = renderWithProviders(<VerifyEmailForm email={EMAIL} />);

        await user.click(firstCell());
        await user.paste("K7QM3WZP");

        expect(await screen.findByText(/That code didn't match or has expired\./, visible)).toBeInTheDocument();
        expect(cells().map((cell) => (cell as HTMLInputElement).value)).toEqual(Array<string>(8).fill(""));
        expect(firstCell()).toHaveFocus();
        expect(screen.getByRole("group", { name: "Verification code" })).toHaveAttribute("aria-invalid", "true");
        expect(resendButton()).toBeEnabled();
        expect(router.replace).not.toHaveBeenCalled();
    });

    it("lets the person try again after a wrong code", async () => {
        const bodies = replyTo(VERIFY_URL, (attempt) => (attempt === 1 ? error(400, "INVALID_TOKEN") : verified()));
        const { user } = renderWithProviders(<VerifyEmailForm email={EMAIL} />);

        await user.click(firstCell());
        await user.paste("K7QM3WZX");
        await screen.findByText(/That code didn't match/, visible);
        await user.paste("K7QM3WZP");

        await waitFor(() => {
            expect(router.replace).toHaveBeenCalled();
        });
        expect(bodies).toEqual([
            { email: EMAIL, code: "K7QM3WZX" },
            { email: EMAIL, code: "K7QM3WZP" },
        ]);
    });

    it("sends a new code and holds the button for 60 seconds on the clock", async () => {
        vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
        const clock = manualClock(TEST_NOW);
        const bodies = replyTo(RESEND_URL, resent);
        const { user } = renderWithProviders(<VerifyEmailForm email={EMAIL} />, { clock });

        await user.click(resendButton());

        expect(
            await screen.findByText("We sent a new code. The old one no longer works.", visible),
        ).toBeInTheDocument();
        expect(bodies).toEqual([{ email: EMAIL }]);
        expect(resendButton()).toBeDisabled();
        expect(resendButton()).toHaveTextContent("Send a new code in 60s");
        expect(firstCell()).toHaveFocus();

        act(() => {
            clock.advance(59_000);
            vi.advanceTimersByTime(250);
        });
        expect(resendButton()).toBeDisabled();
        expect(resendButton()).toHaveTextContent("Send a new code in 1s");

        act(() => {
            clock.advance(1_000);
            vi.advanceTimersByTime(250);
        });
        await waitFor(() => {
            expect(resendButton()).toBeEnabled();
        });
        expect(resendButton()).toHaveTextContent(/^Send a new code$/);
    });

    it("counts a 429 down and holds both actions until it ends", async () => {
        vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
        const clock = manualClock(TEST_NOW);
        replyTo(VERIFY_URL, () => error(429, "TOO_MANY_REQUESTS", { meta: { retryAfter: 15 } }));
        const { user } = renderWithProviders(<VerifyEmailForm email={EMAIL} />, { clock });

        await user.click(firstCell());
        await user.paste("K7QM3WZP");

        expect(
            await screen.findByText("Too many attempts. Try again in 15 seconds.", { selector: "strong" }),
        ).toBeInTheDocument();
        expect(confirmButton()).toBeDisabled();
        expect(resendButton()).toBeDisabled();

        act(() => {
            clock.advance(15_000);
            vi.advanceTimersByTime(250);
        });
        await waitFor(() => {
            expect(confirmButton()).toBeEnabled();
        });
    });

    it("offers a retry that resends the same code after a network failure", async () => {
        const bodies = replyTo(VERIFY_URL, (attempt) => (attempt === 1 ? HttpResponse.error() : verified()));
        const { user } = renderWithProviders(<VerifyEmailForm email={EMAIL} />);

        await user.click(firstCell());
        await user.paste("K7QM3WZP");
        await user.click(await screen.findByRole("button", { name: "Try again" }));

        await waitFor(() => {
            expect(router.replace).toHaveBeenCalled();
        });
        expect(bodies).toEqual([
            { email: EMAIL, code: "K7QM3WZP" },
            { email: EMAIL, code: "K7QM3WZP" },
        ]);
    });

    it("asks for the email first when the link didn't carry one", async () => {
        const bodies = replyTo(VERIFY_URL, verified);
        const { user } = renderWithProviders(<VerifyEmailForm />);

        expect(screen.queryByRole("group", { name: "Verification code" })).not.toBeInTheDocument();
        await user.click(screen.getByRole("button", { name: "Continue" }));
        expect(await screen.findByText("Enter your email address")).toBeInTheDocument();

        await user.type(screen.getByLabelText("Email"), ` ${EMAIL} {Enter}`);

        expect(await screen.findByText(EMAIL)).toBeInTheDocument();
        await user.click(firstCell());
        await user.paste("K7QM3WZP");
        await waitFor(() => {
            expect(bodies).toEqual([{ email: EMAIL, code: "K7QM3WZP" }]);
        });
    });

    it("links back to sign-up for a wrong address", () => {
        renderWithProviders(<VerifyEmailForm email={EMAIL} />);

        expect(screen.getByRole("link", { name: "Start again" })).toHaveAttribute("href", "/signup");
    });
});
