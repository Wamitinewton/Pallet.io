import { manualClock } from "@/test/clock";
import { error, ok } from "@/test/msw/envelopes";
import { bffUrl, server } from "@/test/msw/server";
import { renderWithProviders, TEST_NOW } from "@/test/render";
import { act, screen, waitFor } from "@testing-library/react";
import type { UserEvent } from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { identityKeys } from "./queries";
import { SignupForm } from "./SignupForm";

const router = vi.hoisted(() => ({ replace: vi.fn(), refresh: vi.fn(), push: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router }));

const SIGNUP_URL = bffUrl("/identity/signup");
const AVAILABILITY_URL = bffUrl("/identity/signup/slugs/:slug/availability");

const nameInput = () => screen.getByLabelText("Organization name");
const slugInput = () => screen.getByLabelText("Organization URL");
const displayNameInput = () => screen.getByLabelText("Your name");
const emailInput = () => screen.getByLabelText("Work email");
const passwordInput = () => screen.getByLabelText("Password");
const submitButton = () => screen.getByRole("button", { name: "Create organization" });

async function slugSays(description: string | RegExp) {
    await waitFor(() => {
        expect(slugInput()).toHaveAccessibleDescription(description);
    });
}

interface SentSignup {
    readonly body: unknown;
    readonly idempotencyKey: string | null;
}

function replyToAvailability(available: (slug: string, call: number) => boolean = () => true) {
    const checked: string[] = [];
    server.use(
        http.get(AVAILABILITY_URL, ({ params }) => {
            const slug = String(params.slug);
            checked.push(slug);
            return ok({ slug, available: available(slug, checked.length) });
        }),
    );
    return checked;
}

function replyToSignup(reply: (attempt: number) => Response | Promise<Response>) {
    const sent: SentSignup[] = [];
    server.use(
        http.post(SIGNUP_URL, async ({ request }) => {
            sent.push({ body: await request.json(), idempotencyKey: request.headers.get("Idempotency-Key") });
            return reply(sent.length);
        }),
    );
    return sent;
}

const created = () =>
    ok({ orgId: "org-1", orgName: "Kilima Labs", slug: "kilima-labs" }, "Organization provisioned", { status: 201 });

async function fillForm(user: UserEvent) {
    await user.type(nameInput(), "Kilima Labs");
    await user.type(displayNameInput(), "Amani Otieno");
    await user.type(emailInput(), "amani@kilimalabs.co");
    await user.type(passwordInput(), "correct horse battery");
}

beforeEach(() => {
    vi.clearAllMocks();
});

describe("SignupForm slug", () => {
    it("follows the organization name", async () => {
        replyToAvailability();
        const { user } = renderWithProviders(<SignupForm />);

        await user.type(nameInput(), "Kilima Labs!");

        expect(slugInput()).toHaveValue("kilima-labs");
    });

    it("stops following once the person edits it", async () => {
        replyToAvailability();
        const { user } = renderWithProviders(<SignupForm />);

        await user.type(nameInput(), "Kilima");
        await user.clear(slugInput());
        await user.type(slugInput(), "kilima-hq");
        await user.type(nameInput(), " Labs");

        expect(slugInput()).toHaveValue("kilima-hq");
    });

    it("checks availability once, after typing stops, and shows the address", async () => {
        const checked = replyToAvailability();
        const { user } = renderWithProviders(<SignupForm />);

        await user.type(nameInput(), "Kilima Labs");

        await slugSays("pallet.dev/kilima-labs is available");
        expect(checked).toEqual(["kilima-labs"]);
        expect(slugInput()).not.toHaveAttribute("aria-invalid");
    });

    it("shows a taken slug with alternatives that are checked when picked", async () => {
        const checked = replyToAvailability((slug) => slug !== "kilima");
        const { user } = renderWithProviders(<SignupForm />);

        await user.type(nameInput(), "Kilima Labs");
        await user.clear(slugInput());
        await user.type(slugInput(), "kilima");

        await slugSays(/^Someone already uses kilima\. Try kilima-labs or kilima-hq/);
        expect(slugInput()).toHaveAttribute("aria-invalid", "true");
        expect(screen.getAllByRole("status").map((region) => region.textContent)).toContain(
            "Someone already uses kilima.",
        );

        await user.click(screen.getByRole("button", { name: "kilima-hq" }));

        expect(slugInput()).toHaveValue("kilima-hq");
        expect(slugInput()).toHaveFocus();
        await slugSays("pallet.dev/kilima-hq is available");
        expect(checked).toContain("kilima-hq");
    });

    it("explains a slug the pattern refuses without asking the server", async () => {
        const checked = replyToAvailability();
        const { user } = renderWithProviders(<SignupForm />);

        await user.type(slugInput(), "kilima-");

        expect(await screen.findByText(/single hyphens/)).toBeInTheDocument();
        await new Promise((resolve) => setTimeout(resolve, 500));
        expect(checked).toEqual([]);
    });

    it("cancels a check that is still running when the slug changes", async () => {
        server.use(
            http.get(AVAILABILITY_URL, async ({ params }) => {
                if (params.slug === "kilima") await new Promise((resolve) => setTimeout(resolve, 2_000));
                return ok({ slug: params.slug, available: true });
            }),
        );
        const { user, queryClient } = renderWithProviders(<SignupForm />);
        const firstCheck = () => queryClient.getQueryState(identityKeys.slugAvailability("kilima"));

        await user.type(slugInput(), "kilima");
        await waitFor(() => {
            expect(firstCheck()?.fetchStatus).toBe("fetching");
        });
        await user.type(slugInput(), "-labs");

        await slugSays("pallet.dev/kilima-labs is available");
        expect(firstCheck()).toMatchObject({ fetchStatus: "idle", status: "pending", data: undefined });
    });

    it("lets the person continue when the check fails", async () => {
        server.use(http.get(AVAILABILITY_URL, () => error(503, "SERVICE_UNAVAILABLE")));
        const sent = replyToSignup(created);
        const { user } = renderWithProviders(<SignupForm />);

        await fillForm(user);
        expect(await screen.findByText(/couldn't check this URL/)).toBeInTheDocument();
        await user.click(submitButton());

        await waitFor(() => {
            expect(sent).toHaveLength(1);
        });
    });
});

describe("SignupForm submit", () => {
    it("creates the organization and moves on to email confirmation", async () => {
        replyToAvailability();
        const sent = replyToSignup(created);
        const { user } = renderWithProviders(<SignupForm />);

        await fillForm(user);
        await user.click(submitButton());

        await waitFor(() => {
            expect(router.replace).toHaveBeenCalledWith("/verify-email?email=amani%40kilimalabs.co");
        });
        expect(sent).toEqual([
            {
                body: {
                    organizationName: "Kilima Labs",
                    slug: "kilima-labs",
                    email: "amani@kilimalabs.co",
                    displayName: "Amani Otieno",
                    password: "correct horse battery",
                },
                idempotencyKey: expect.stringMatching(/^[0-9a-f-]{36}$/) as unknown,
            },
        ]);
    });

    it("validates every field before sending anything", async () => {
        const sent = replyToSignup(created);
        const { user } = renderWithProviders(<SignupForm />);

        await user.type(passwordInput(), "short");
        await user.click(submitButton());

        expect(await screen.findByText("Enter your organization's name")).toBeInTheDocument();
        expect(screen.getByText("Choose a URL for your organization")).toBeInTheDocument();
        expect(screen.getByText("Enter your name")).toBeInTheDocument();
        expect(screen.getByText("Enter your email address")).toBeInTheDocument();
        expect(screen.getByText("Use at least 12 characters")).toBeInTheDocument();
        expect(nameInput()).toHaveFocus();
        expect(sent).toEqual([]);
    });

    it("puts a 409 on the slug when the re-check says it is now taken", async () => {
        replyToAvailability((_, call) => call === 1);
        replyToSignup(() =>
            error(409, "CONFLICT", { message: "An organization with slug 'kilima-labs' already exists" }),
        );
        const { user } = renderWithProviders(<SignupForm />);

        await fillForm(user);
        await slugSays("pallet.dev/kilima-labs is available");
        await user.click(submitButton());

        await slugSays(/^Someone already uses kilima-labs\. Try kilima-labs-hq or kilima-labs-team/);
        expect(slugInput()).toHaveFocus();
        expect(emailInput()).not.toHaveAttribute("aria-invalid");
        expect(router.replace).not.toHaveBeenCalled();
    });

    it("puts a 409 on the email, with a way to sign in, when the slug is still free", async () => {
        replyToAvailability();
        replyToSignup(() => error(409, "CONFLICT", { message: "An account with this email already exists" }));
        const { user } = renderWithProviders(<SignupForm />);

        await fillForm(user);
        await user.click(submitButton());

        expect(await screen.findByText(/An account with this email already exists\./)).toBeInTheDocument();
        expect(emailInput()).toHaveAttribute("aria-invalid", "true");
        expect(emailInput()).toHaveFocus();
        expect(screen.getByRole("link", { name: "Sign in instead" })).toHaveAttribute(
            "href",
            "/login?email=amani%40kilimalabs.co",
        );
    });

    it("says the URL or email is taken when the re-check can't tell which", async () => {
        let checks = 0;
        server.use(
            http.get(AVAILABILITY_URL, ({ params }) =>
                ++checks === 1 ? ok({ slug: params.slug, available: true }) : HttpResponse.error(),
            ),
        );
        replyToSignup(() => error(409, "CONFLICT"));
        const { user } = renderWithProviders(<SignupForm />);

        await fillForm(user);
        await slugSays("pallet.dev/kilima-labs is available");
        await user.click(submitButton());

        expect(
            await screen.findByText(/organization URL or email is already in use/, { ignore: "[role=status]" }),
        ).toBeInTheDocument();
    });

    it("sends the same Idempotency-Key when retrying after a network failure", async () => {
        replyToAvailability();
        const sent = replyToSignup((attempt) => (attempt === 1 ? HttpResponse.error() : created()));
        const { user } = renderWithProviders(<SignupForm />);

        await fillForm(user);
        await user.click(submitButton());
        await user.click(await screen.findByRole("button", { name: "Try again" }));

        await waitFor(() => {
            expect(router.replace).toHaveBeenCalledWith("/verify-email?email=amani%40kilimalabs.co");
        });
        expect(sent).toHaveLength(2);
        expect(sent[1]?.idempotencyKey).toBe(sent[0]?.idempotencyKey);
    });

    it("sends a new Idempotency-Key once a field changes", async () => {
        replyToAvailability();
        const sent = replyToSignup((attempt) => (attempt === 1 ? HttpResponse.error() : created()));
        const { user } = renderWithProviders(<SignupForm />);

        await fillForm(user);
        await user.click(submitButton());
        await screen.findByRole("button", { name: "Try again" });
        await user.type(displayNameInput(), "-Kamau");
        await user.click(submitButton());

        await waitFor(() => {
            expect(sent).toHaveLength(2);
        });
        expect(sent[1]?.idempotencyKey).not.toBe(sent[0]?.idempotencyKey);
    });

    it("lands the backend's field errors on their fields", async () => {
        replyToAvailability();
        replyToSignup(() =>
            error(400, "VALIDATION_ERROR", {
                validationErrors: [{ field: "password", message: "must be at least 12 characters" }],
            }),
        );
        const { user } = renderWithProviders(<SignupForm />);

        await fillForm(user);
        await user.click(submitButton());

        expect(await screen.findByText("must be at least 12 characters")).toBeInTheDocument();
        expect(passwordInput()).toHaveAttribute("aria-invalid", "true");
        expect(screen.queryByRole("button", { name: "Try again" })).not.toBeInTheDocument();
    });

    it("counts a 429 down and holds submit until it ends", async () => {
        vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
        const clock = manualClock(TEST_NOW);
        replyToAvailability();
        replyToSignup(() => error(429, "TOO_MANY_REQUESTS", { meta: { retryAfter: 20 } }));
        const { user } = renderWithProviders(<SignupForm />, { clock });

        await fillForm(user);
        await user.click(submitButton());

        expect(
            await screen.findByText("Too many attempts. Try again in 20 seconds.", { selector: "strong" }),
        ).toBeInTheDocument();
        expect(submitButton()).toBeDisabled();

        act(() => {
            clock.advance(20_000);
            vi.advanceTimersByTime(250);
        });
        await waitFor(() => {
            expect(submitButton()).toBeEnabled();
        });
    });
});

describe("SignupForm password", () => {
    it("rates the password as it is typed without blocking a weak one that meets the policy", async () => {
        const { user } = renderWithProviders(<SignupForm />);
        const meter = () => screen.getByRole("meter", { name: "Password strength" });

        expect(meter()).toHaveAttribute("aria-valuenow", "0");
        await user.type(passwordInput(), "correct-horse-battery");

        expect(meter()).toHaveAttribute("aria-valuenow", "3");
        expect(meter()).toHaveAttribute("aria-valuetext", "Good");
    });
});
