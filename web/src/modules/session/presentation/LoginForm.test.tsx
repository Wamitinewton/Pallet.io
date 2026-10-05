import { manualClock } from "@/test/clock";
import { error, ok } from "@/test/msw/envelopes";
import { server, sessionApiUrl } from "@/test/msw/server";
import { renderWithProviders, TEST_NOW } from "@/test/render";
import { act, screen, waitFor } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { LoginForm } from "./LoginForm";

const router = vi.hoisted(() => ({ replace: vi.fn(), refresh: vi.fn(), push: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router }));

const emailInput = () => screen.getByLabelText("Email");
const passwordInput = () => screen.getByLabelText("Password");
const submitButton = () => screen.getByRole("button", { name: "Sign in" });
const calloutLead = (text: string | RegExp) => screen.findByText(text, { selector: "strong" });

async function signInWith(user: UserEvent, email = "ada@example.com", password = "correct horse") {
    await user.type(emailInput(), email);
    await user.type(passwordInput(), password);
    await user.click(submitButton());
}

function replyToLogin(reply: () => Response | Promise<Response>) {
    const bodies: unknown[] = [];
    server.use(
        http.post(sessionApiUrl("/login"), async ({ request }) => {
            bodies.push(await request.json());
            return reply();
        }),
    );
    return bodies;
}

beforeEach(() => {
    vi.clearAllMocks();
});

describe("LoginForm", () => {
    it("signs in, empties the cache and follows next", async () => {
        const bodies = replyToLogin(() => ok({ ok: true }, "Signed in"));
        const { user, queryClient } = renderWithProviders(<LoginForm next="/orgs/o-1/apps?page=2" />);
        queryClient.setQueryData(["org", "o-0", "apps"], ["from a previous account"]);

        await signInWith(user);

        await waitFor(() => {
            expect(router.replace).toHaveBeenCalledWith("/orgs/o-1/apps?page=2");
        });
        expect(bodies).toEqual([{ email: "ada@example.com", password: "correct horse" }]);
        expect(queryClient.getQueryData(["org", "o-0", "apps"])).toBeUndefined();
    });

    it("ignores an unsafe next and lands on /orgs", async () => {
        replyToLogin(() => ok({ ok: true }));
        const { user } = renderWithProviders(<LoginForm next="//evil.example/orgs" />);

        await signInWith(user);

        await waitFor(() => {
            expect(router.replace).toHaveBeenCalledWith("/orgs");
        });
    });

    it("clears and focuses the password on INVALID_CREDENTIALS, keeping the email", async () => {
        replyToLogin(() => error(401, "INVALID_CREDENTIALS", { message: "Invalid email or password" }));
        const { user } = renderWithProviders(<LoginForm />);

        await signInWith(user, "ada@example.com", "wrong");

        expect(await calloutLead("Email or password is incorrect.")).toBeInTheDocument();
        expect(passwordInput()).toHaveValue("");
        expect(passwordInput()).toHaveFocus();
        expect(emailInput()).toHaveValue("ada@example.com");
        expect(screen.getByRole("status")).toHaveTextContent("Email or password is incorrect.");
        expect(screen.getByRole("link", { name: "reset your password" })).toHaveAttribute(
            "href",
            "/forgot-password?email=ada%40example.com",
        );
        expect(router.replace).not.toHaveBeenCalled();
    });

    it("links an unverified account to email confirmation with its address", async () => {
        replyToLogin(() => error(403, "EMAIL_NOT_VERIFIED"));
        const { user } = renderWithProviders(<LoginForm />);

        await signInWith(user, "new@example.com");

        expect(await calloutLead("Confirm your email first.")).toBeInTheDocument();
        expect(screen.getByText(/We sent a code to new@example.com/)).toBeInTheDocument();
        expect(screen.getByRole("link", { name: "Enter code" })).toHaveAttribute(
            "href",
            "/verify-email?email=new%40example.com",
        );
    });

    it("counts a 429 down on the clock and holds submit until it reaches zero", async () => {
        vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
        const clock = manualClock(TEST_NOW);
        replyToLogin(() => error(429, "TOO_MANY_REQUESTS", { meta: { retryAfter: 30 } }));
        const { user } = renderWithProviders(<LoginForm />, { clock });

        await signInWith(user);

        expect(await calloutLead("Too many attempts. Try again in 30 seconds.")).toBeInTheDocument();
        expect(submitButton()).toBeDisabled();
        expect(screen.getByRole("status")).toHaveTextContent("Too many attempts. Try again in 30 seconds.");

        act(() => {
            clock.advance(1_000);
            vi.advanceTimersByTime(250);
        });
        expect(await calloutLead("Too many attempts. Try again in 29 seconds.")).toBeInTheDocument();
        expect(submitButton()).toBeDisabled();

        act(() => {
            clock.advance(29_000);
            vi.advanceTimersByTime(250);
        });
        await waitFor(() => {
            expect(submitButton()).toBeEnabled();
        });
        expect(screen.queryByText(/Try again in/, { selector: "strong" })).not.toBeInTheDocument();
    });

    it("reads the wait from Retry-After when meta has none", async () => {
        replyToLogin(() => error(429, "TOO_MANY_REQUESTS", { headers: { "Retry-After": "12" } }));
        const { user } = renderWithProviders(<LoginForm />);

        await signInWith(user);

        expect(await calloutLead("Too many attempts. Try again in 12 seconds.")).toBeInTheDocument();
    });

    it("confirms a verified email and prefills it", () => {
        renderWithProviders(<LoginForm verified email="amani@kilimalabs.co" />);

        expect(screen.getByText("Email confirmed.", { selector: "strong" })).toBeInTheDocument();
        expect(emailInput()).toHaveValue("amani@kilimalabs.co");
        expect(screen.queryByText(/You were signed out/)).not.toBeInTheDocument();
    });

    it("confirms a password reset and points at the sessions still signed in", () => {
        renderWithProviders(<LoginForm reset />);

        expect(screen.getByText("Password updated.", { selector: "strong" })).toBeInTheDocument();
        expect(screen.getByText(/Sign in with your new password\./)).toHaveTextContent(/under Your account/);
    });

    it("explains an expired session on arrival", () => {
        renderWithProviders(<LoginForm reason="expired" />);

        expect(screen.getByText(/You were signed out/)).toBeInTheDocument();
    });

    it("offers a retry after a network failure and keeps what was typed", async () => {
        let attempts = 0;
        replyToLogin(() => (++attempts === 1 ? HttpResponse.error() : ok({ ok: true })));
        const { user } = renderWithProviders(<LoginForm />);

        await signInWith(user);

        expect(await screen.findByRole("button", { name: "Try again" })).toBeInTheDocument();
        expect(screen.getByRole("status")).toHaveTextContent("We couldn't reach Pallet.");
        expect(emailInput()).toHaveValue("ada@example.com");
        expect(passwordInput()).toHaveValue("correct horse");

        await user.click(screen.getByRole("button", { name: "Try again" }));

        await waitFor(() => {
            expect(router.replace).toHaveBeenCalledWith("/orgs");
        });
    });

    it("shows a server error with its reference", async () => {
        replyToLogin(() => error(500, "INTERNAL_ERROR"));
        const { user } = renderWithProviders(<LoginForm />);

        await signInWith(user);

        expect(await screen.findByText(/Something went wrong.*Reference: corr-test-0001/)).toBeInTheDocument();
        expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
    });

    it("places server field errors on their fields", async () => {
        replyToLogin(() =>
            error(400, "VALIDATION_ERROR", { validationErrors: [{ field: "email", message: "Email is malformed" }] }),
        );
        const { user } = renderWithProviders(<LoginForm />);

        await signInWith(user);

        expect(await screen.findByText("Email is malformed")).toBeInTheDocument();
        expect(emailInput()).toHaveAttribute("aria-invalid", "true");
    });

    it("validates before sending anything", async () => {
        const bodies = replyToLogin(() => ok({ ok: true }));
        const { user } = renderWithProviders(<LoginForm />);

        await user.click(submitButton());

        expect(await screen.findByText("Enter your email address")).toBeInTheDocument();
        expect(screen.getByText("Enter your password")).toBeInTheDocument();
        expect(bodies).toEqual([]);
    });

    it("submits from the keyboard", async () => {
        const bodies = replyToLogin(() => ok({ ok: true }));
        const { user } = renderWithProviders(<LoginForm />);

        await user.tab();
        expect(emailInput()).toHaveFocus();
        await user.keyboard("ada@example.com");
        await user.tab();
        await user.tab();
        expect(passwordInput()).toHaveFocus();
        await user.keyboard("correct horse{Enter}");

        await waitFor(() => {
            expect(bodies).toHaveLength(1);
        });
    });

    it("prefills the forgot-password link with the typed email", async () => {
        const { user } = renderWithProviders(<LoginForm />);

        await user.type(emailInput(), "ada@example.com");

        expect(screen.getByRole("link", { name: "Forgot password?" })).toHaveAttribute(
            "href",
            "/forgot-password?email=ada%40example.com",
        );
    });
});
