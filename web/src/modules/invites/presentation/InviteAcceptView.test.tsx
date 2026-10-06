import { error, ok, page } from "@/test/msw/envelopes";
import { sessionSummaryBody } from "@/test/msw/identity";
import { invitePreviewDto, orgSummaryDto } from "@/test/msw/org-team";
import { bffUrl, server, sessionApiUrl } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { anInviteToken } from "../domain/testing/fixtures";
import { InviteAcceptView, type InviteAcceptViewProps } from "./InviteAcceptView";

const router = vi.hoisted(() => ({ replace: vi.fn(), refresh: vi.fn(), push: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router }));

const TOKEN = anInviteToken();
const LAPSED_TOKEN = anInviteToken({ exp: Date.parse("2026-01-15T11:00:00Z") / 1000 });
const PASSWORD = "mango-season-in-kisumu";

const previewUrl = (token: string) => bffUrl(`/org-team/invites/${token}`);
const acceptUrl = (token: string) => bffUrl(`/identity/invites/${token}/accept`);
const signInToAcceptHref = (token: string) => `/login?next=${encodeURIComponent(`/invites/${token}`)}`;

const passwordInput = () => screen.getByLabelText("Password");
const acceptButton = () => screen.getByRole("button", { name: "Accept and join" });
const accepted = () => ok(null, "Invite accepted", { status: 201 });

function previewReplies(reply: () => Response) {
    server.use(http.get(previewUrl(TOKEN), reply), http.get(previewUrl(LAPSED_TOKEN), reply));
}

function acceptReplies(reply: () => Response | Promise<Response>) {
    const bodies: unknown[] = [];
    server.use(
        http.post(acceptUrl(TOKEN), async ({ request }) => {
            bodies.push(await request.json());
            return reply();
        }),
    );
    return bodies;
}

function renderView(props: Partial<InviteAcceptViewProps> = {}) {
    return renderWithProviders(
        <InviteAcceptView token={TOKEN} signedIn={false} canJoinAsSignedIn={false} {...props} />,
    );
}

beforeEach(() => {
    vi.clearAllMocks();
    previewReplies(() => ok(invitePreviewDto(), "Invite retrieved"));
    server.use(http.get(sessionApiUrl(""), () => ok(sessionSummaryBody(), "Current session")));
});

describe("InviteAcceptView", () => {
    describe("for someone new to Pallet", () => {
        it("says who invited which address to what, with which role, and until when", async () => {
            renderView();

            expect(await screen.findByRole("heading", { level: 1, name: "Join Kilima Labs" })).toBeInTheDocument();
            const invitation = screen.getByText("Grace Njeri").closest("p");
            expect(invitation).toHaveTextContent(
                "Grace Njeri invited d***@kilimalabs.co to join Kilima Labs on Pallet as a Developer.",
            );
            expect(
                screen.getByText(/Developers can create and edit apps, change build settings and start builds\./),
            ).toBeInTheDocument();
            expect(screen.getByText(/This invite expires on/).querySelector("time")).toHaveAttribute(
                "datetime",
                "2026-01-17T12:00:00.000Z",
            );
            expect(screen.getByRole("form", { name: "Set a password to finish" })).toBeInTheDocument();
            expect(screen.getByRole("link", { name: "Sign in to accept" })).toHaveAttribute(
                "href",
                signInToAcceptHref(TOKEN),
            );
        });

        it("creates the account and sends the invitee to sign in with the organization named", async () => {
            const bodies = acceptReplies(accepted);
            const { user } = renderView();

            await user.type(await screen.findByLabelText("Password"), PASSWORD);
            await user.click(acceptButton());

            await waitFor(() => {
                expect(router.replace).toHaveBeenCalledWith("/login?joined=Kilima+Labs");
            });
            expect(bodies).toEqual([{ password: PASSWORD }]);
        });

        it("holds a password the policy refuses and sends nothing", async () => {
            const bodies = acceptReplies(accepted);
            const { user } = renderView();

            await user.type(await screen.findByLabelText("Password"), "short");
            await user.click(acceptButton());

            expect(await screen.findByText("Use at least 12 characters")).toBeInTheDocument();
            expect(passwordInput()).toHaveAttribute("aria-invalid", "true");
            expect(bodies).toEqual([]);
        });

        it("puts the backend's field error on the password", async () => {
            acceptReplies(() =>
                error(400, "VALIDATION_FAILED", {
                    validationErrors: [{ field: "password", message: "must not be blank" }],
                }),
            );
            const { user } = renderView();

            await user.type(await screen.findByLabelText("Password"), PASSWORD);
            await user.click(acceptButton());

            expect(await screen.findByText("must not be blank")).toBeInTheDocument();
        });
    });

    describe("for an address that already has an account", () => {
        it("switches to signing in when creating the account answers CONFLICT", async () => {
            acceptReplies(() => error(409, "CONFLICT", { message: "An account with this email already exists" }));
            const { user } = renderView();

            await user.type(await screen.findByLabelText("Password"), PASSWORD);
            await user.click(acceptButton());

            expect(
                await screen.findByText(/This email already has a Pallet account\. Sign in, then open the invite link/),
            ).toBeInTheDocument();
            expect(screen.queryByRole("form", { name: "Set a password to finish" })).not.toBeInTheDocument();
            expect(screen.getByRole("link", { name: "Sign in to accept" })).toHaveAttribute(
                "href",
                signInToAcceptHref(TOKEN),
            );
            await waitFor(() => {
                expect(screen.getByText(/already has a Pallet account/).closest("[tabindex='-1']")).toHaveFocus();
            });
        });

        it("explains to a signed-in visitor that joining with it isn't available yet", async () => {
            renderView({ signedIn: true });

            expect(
                await screen.findByText(/You're signed in as amani@kilimalabs\.co\. Joining Kilima Labs/),
            ).toBeInTheDocument();
            expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
            expect(screen.queryByRole("form", { name: "Set a password to finish" })).not.toBeInTheDocument();
        });

        it("joins as the signed-in account and opens the organization once it appears", async () => {
            const bodies = acceptReplies(accepted);
            server.use(http.get(bffUrl("/org-team/orgs"), () => page([orgSummaryDto({ myRole: "DEVELOPER" })])));
            const { user } = renderView({ signedIn: true, canJoinAsSignedIn: true });

            await user.click(await screen.findByRole("button", { name: "Join Kilima Labs as amani@kilimalabs.co" }));

            await waitFor(() => {
                expect(router.replace).toHaveBeenCalledWith("/orgs/org-kilima");
            });
            expect(bodies).toEqual([{}]);
        });

        it("tells a signed-in account the invite was sent to someone else", async () => {
            acceptReplies(() => error(403, "INVITE_EMAIL_MISMATCH"));
            const { user } = renderView({ signedIn: true, canJoinAsSignedIn: true });

            await user.click(await screen.findByRole("button", { name: "Join Kilima Labs as amani@kilimalabs.co" }));

            expect(await screen.findByRole("alert")).toHaveTextContent(
                "This invite was sent to d***@kilimalabs.co. Sign out and sign in with that account.",
            );
            expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
        });
    });

    describe("for a link that no longer works", () => {
        it("says an expired link has expired, and whom to ask", async () => {
            previewReplies(() => error(400, "INVALID_TOKEN"));
            renderView({ token: LAPSED_TOKEN });

            expect(await screen.findByRole("heading", { name: "This invite has expired" })).toBeInTheDocument();
            expect(
                screen.getByText(
                    "Invites stay open for 3 days. Ask the person who invited you or another admin to send you a new one.",
                ),
            ).toBeInTheDocument();
            expect(screen.getByRole("link", { name: "Go to pallet.dev" })).toHaveAttribute("href", "/");
        });

        it("says a withdrawn invite is no longer valid", async () => {
            previewReplies(() => error(410, "INVITE_NO_LONGER_VALID"));
            renderView();

            expect(await screen.findByRole("heading", { name: "This invite is no longer valid" })).toBeInTheDocument();
            expect(screen.getByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/login");
        });

        it("reads a link that isn't a token as no longer valid without asking", async () => {
            const previews: Request[] = [];
            server.use(
                http.get(bffUrl("/org-team/invites/:token"), ({ request }) => {
                    previews.push(request);
                    return ok(invitePreviewDto());
                }),
            );
            renderView({ token: "not-a-token" });

            expect(await screen.findByRole("heading", { name: "This invite is no longer valid" })).toBeInTheDocument();
            expect(previews).toEqual([]);
        });

        it("shows withdrawn when the invite was used before the form was sent", async () => {
            acceptReplies(() => error(409, "INVITE_ALREADY_CONSUMED"));
            const { user } = renderView();

            await user.type(await screen.findByLabelText("Password"), PASSWORD);
            await user.click(acceptButton());

            const heading = await screen.findByRole("heading", { name: "This invite is no longer valid" });
            await waitFor(() => {
                expect(heading.closest("[tabindex='-1']")).toHaveFocus();
            });
            expect(screen.queryByRole("heading", { name: "Join Kilima Labs" })).not.toBeInTheDocument();
        });

        it("names the inviter once the invite was read, if the link lapses while it is open", async () => {
            server.use(http.get(previewUrl(LAPSED_TOKEN), () => ok(invitePreviewDto())));
            server.use(http.post(acceptUrl(LAPSED_TOKEN), () => error(400, "INVALID_TOKEN")));
            const { user } = renderView({ token: LAPSED_TOKEN });

            await user.type(await screen.findByLabelText("Password"), PASSWORD);
            await user.click(acceptButton());

            expect(await screen.findByText(/Ask Grace Njeri or another admin/)).toBeInTheDocument();
        });
    });

    it("offers a retry when the preview can't be read", async () => {
        let attempts = 0;
        previewReplies(() => (++attempts === 1 ? HttpResponse.error() : ok(invitePreviewDto())));
        const { user } = renderView();

        await user.click(await screen.findByRole("button", { name: "Try again" }));

        expect(await screen.findByRole("heading", { level: 1, name: "Join Kilima Labs" })).toBeInTheDocument();
    });
});
