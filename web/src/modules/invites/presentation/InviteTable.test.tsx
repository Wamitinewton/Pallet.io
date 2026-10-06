import { error } from "@/test/msw/envelopes";
import { screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { DAVID, InvitesBackend, LUCY, renderInvites } from "./testing/render-invites";

type Rendered = Awaited<ReturnType<typeof renderInvites>>;

const rowOf = (email: string) => screen.getByRole("row", { name: new RegExp(email) });

async function confirmRevoke({ user }: Rendered, email: string) {
    await user.click(screen.getByRole("button", { name: `Revoke invite to ${email}` }));
    const dialog = await screen.findByRole("dialog", { name: "Revoke this invite?" });
    expect(dialog).toHaveTextContent(`The link sent to ${email} stops working.`);
    await user.click(within(dialog).getByRole("button", { name: "Revoke invite" }));
}

function deferred() {
    let release: () => void = () => undefined;
    const released = new Promise<void>((resolve) => {
        release = resolve;
    });
    return { released, release };
}

describe("InviteTable resend", () => {
    it("emails the invite again and shows its new send count", async () => {
        const rendered = await renderInvites();

        await rendered.user.click(screen.getByRole("button", { name: `Resend invite to ${DAVID.email}` }));

        expect(await screen.findByText(`Invite sent again to ${DAVID.email}`)).toBeInTheDocument();
        expect(within(rowOf(DAVID.email)).getByText("3 of 4 emails")).toBeInTheDocument();
        expect(within(rowOf(DAVID.email)).getByText("in 3 days")).toBeInTheDocument();
        expect(rendered.backend.resent).toEqual([DAVID.id]);
    });

    it("counts down the cooldown the backend asks for, without sending again", async () => {
        const backend = new InvitesBackend();
        backend.resendReply = () =>
            error(429, "TOO_MANY_REQUESTS", {
                message: "This invitation was sent recently. Try again shortly.",
                headers: { "Retry-After": "180" },
            });
        const rendered = await renderInvites({ backend });

        await rendered.user.click(screen.getByRole("button", { name: `Resend invite to ${DAVID.email}` }));

        expect(
            await screen.findByText("This invite was emailed moments ago. You can send it again in 3 min."),
        ).toBeInTheDocument();
        const cooling = within(rowOf(DAVID.email)).getByRole("button", { name: /^Resend in 3 min/ });
        expect(cooling).toHaveAttribute("aria-disabled", "true");

        await rendered.user.click(cooling);
        expect(backend.resent).toEqual([DAVID.id]);
        expect(within(rowOf(LUCY.email)).getByRole("button", { name: /^Resend invite/ })).not.toHaveAttribute(
            "aria-disabled",
        );
    });

    it("shows the backend's own words once the send cap is reached", async () => {
        const backend = new InvitesBackend();
        backend.resendReply = () =>
            error(409, "QUOTA_EXCEEDED", { message: "This invitation has reached its resend limit." });
        const rendered = await renderInvites({ backend });

        await rendered.user.click(screen.getByRole("button", { name: `Resend invite to ${DAVID.email}` }));

        expect(await screen.findByText("This invitation has reached its resend limit.")).toBeInTheDocument();
    });

    it("reads the list again when the invite stopped being pending", async () => {
        const backend = new InvitesBackend();
        backend.resendReply = (invite) => {
            backend.replace({ ...invite, status: "ACCEPTED" });
            return error(409, "INVITE_NOT_PENDING");
        };
        const rendered = await renderInvites({ backend });

        await rendered.user.click(screen.getByRole("button", { name: `Resend invite to ${DAVID.email}` }));

        expect(await screen.findByText(`The invite to ${DAVID.email} is no longer pending.`)).toBeInTheDocument();
        await waitFor(() => {
            expect(screen.queryByRole("row", { name: new RegExp(DAVID.email) })).not.toBeInTheDocument();
        });
    });
});

describe("InviteTable revoke", () => {
    it("takes the invite off the pending list before the backend answers", async () => {
        const backend = new InvitesBackend();
        const gate = deferred();
        backend.revokeReply = async (invite) => {
            await gate.released;
            backend.replace({ ...invite, status: "REVOKED" });
            return new Response(null, { status: 204 });
        };
        const rendered = await renderInvites({ backend });

        await confirmRevoke(rendered, LUCY.email);

        await waitFor(() => {
            expect(screen.queryByRole("row", { name: new RegExp(LUCY.email) })).not.toBeInTheDocument();
        });
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        expect(screen.getByText("1 pending invite")).toBeInTheDocument();

        gate.release();

        expect(await screen.findByText(`Invite to ${LUCY.email} revoked`)).toBeInTheDocument();
        expect(backend.revoked).toEqual([LUCY.id]);
    });

    it("says what the invite became when someone got there first", async () => {
        const backend = new InvitesBackend();
        backend.revokeReply = (invite) => {
            backend.replace({ ...invite, status: "ACCEPTED" });
            return error(409, "INVITE_NOT_PENDING");
        };
        const rendered = await renderInvites({ backend });

        await confirmRevoke(rendered, LUCY.email);

        expect(
            await screen.findByText(`${LUCY.email} accepted the invite before it could be revoked.`),
        ).toBeInTheDocument();
        expect(backend.listed("status")).toContain(null);
        expect(screen.queryByRole("row", { name: new RegExp(LUCY.email) })).not.toBeInTheDocument();
    });

    it("treats the backend's not found for a settled invite the same way", async () => {
        const backend = new InvitesBackend();
        backend.revokeReply = (invite) => {
            backend.replace({ ...invite, status: "REVOKED" });
            return error(404, "INVITE_NOT_FOUND");
        };
        const rendered = await renderInvites({ backend });

        await confirmRevoke(rendered, DAVID.email);

        expect(await screen.findByText(`The invite to ${DAVID.email} was already revoked.`)).toBeInTheDocument();
    });

    it("puts the row back when the revoke is refused", async () => {
        const backend = new InvitesBackend();
        backend.revokeReply = () => error(403, "INSUFFICIENT_ROLE");
        const rendered = await renderInvites({ backend });

        await confirmRevoke(rendered, DAVID.email);

        expect(await screen.findByText("Only the owner can revoke an invite that grants admin.")).toBeInTheDocument();
        expect(rowOf(DAVID.email)).toBeInTheDocument();
        expect(screen.getByText("2 pending invites")).toBeInTheDocument();
    });

    it("keeps the invite when the dialog is dismissed", async () => {
        const rendered = await renderInvites();

        await rendered.user.click(screen.getByRole("button", { name: `Revoke invite to ${LUCY.email}` }));
        await rendered.user.click(await screen.findByRole("button", { name: "Keep it" }));

        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        expect(rendered.backend.revoked).toEqual([]);
        expect(rowOf(LUCY.email)).toBeInTheDocument();
    });
});
