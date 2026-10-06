import { error } from "@/test/msw/envelopes";
import { screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { GRACE, InvitesBackend, LUCY, OTIENO, renderInvites } from "./testing/render-invites";

type Rendered = Awaited<ReturnType<typeof renderInvites>>;

async function openDialog({ user }: Rendered) {
    await user.click(screen.getByRole("button", { name: "Invite people" }));
    return screen.findByRole("dialog", { name: "Invite someone to Kilima Labs" });
}

const roleOptions = (dialog: HTMLElement) =>
    within(within(dialog).getByRole("radiogroup", { name: "Role" }))
        .getAllByRole("radio")
        .map((radio) => radio.textContent);

async function invite(rendered: Rendered, email: string, role?: string) {
    const dialog = await openDialog(rendered);
    await rendered.user.type(within(dialog).getByRole("textbox", { name: "Email address" }), email);
    if (role !== undefined) await rendered.user.click(within(dialog).getByRole("radio", { name: new RegExp(role) }));
    await rendered.user.click(within(dialog).getByRole("button", { name: "Send invite" }));
    return dialog;
}

describe("InviteDialog", () => {
    it("lets an owner grant admin, developer or viewer, starting on developer", async () => {
        const dialog = await openDialog(await renderInvites());

        expect(roleOptions(dialog)).toEqual([
            expect.stringMatching(/^Admin/),
            expect.stringMatching(/^Developer/),
            expect.stringMatching(/^Viewer/),
        ]);
        expect(within(dialog).getByRole("radio", { name: /^Developer/ })).toBeChecked();
        expect(within(dialog).getByRole("textbox", { name: "Email address" })).toHaveFocus();
    });

    it("lets an admin grant developer or viewer only", async () => {
        const dialog = await openDialog(await renderInvites({ backend: new InvitesBackend(undefined, GRACE) }));

        expect(roleOptions(dialog)).toEqual([expect.stringMatching(/^Developer/), expect.stringMatching(/^Viewer/)]);
    });

    it("sends the invite, closes, and shows it in the pending list", async () => {
        const rendered = await renderInvites();

        await invite(rendered, "  Mercy@KilimaLabs.co ", "Viewer");

        expect(await screen.findByText("Invite sent to mercy@kilimalabs.co")).toBeInTheDocument();
        expect(rendered.backend.created).toEqual([{ email: "mercy@kilimalabs.co", role: "VIEWER" }]);
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        expect(await screen.findByRole("row", { name: /mercy@kilimalabs\.co/ })).toBeInTheDocument();
    });

    it("checks the address before sending anything", async () => {
        const rendered = await renderInvites();
        const dialog = await openDialog(rendered);

        await rendered.user.click(within(dialog).getByRole("button", { name: "Send invite" }));
        expect(within(dialog).getByRole("textbox", { name: "Email address" })).toHaveAccessibleDescription(
            "Enter their email address",
        );

        await rendered.user.type(within(dialog).getByRole("textbox", { name: "Email address" }), "mercy@");
        await rendered.user.click(within(dialog).getByRole("button", { name: "Send invite" }));
        expect(within(dialog).getByRole("textbox", { name: "Email address" })).toHaveAccessibleDescription(
            "Enter a valid email address",
        );
        expect(rendered.backend.created).toEqual([]);
    });

    it.each([
        ["ALREADY_A_MEMBER", "They're already in this organization."],
        ["MEMBER_PREVIOUSLY_REMOVED", "They were removed from this organization; an owner must re-add them."],
        ["INVITE_ALREADY_PENDING", "An invite to this address is already pending."],
    ])("puts %s on the email field", async (code, message) => {
        const backend = new InvitesBackend();
        backend.createReply = () => error(409, code);
        const rendered = await renderInvites({ backend });

        const dialog = await invite(rendered, "grace.njeri@kilimalabs.co");

        const email = within(dialog).getByRole("textbox", { name: "Email address" });
        await waitFor(() => {
            expect(email).toHaveAccessibleDescription(message);
        });
        expect(email).toHaveFocus();
        expect(within(dialog).queryByRole("alert")).not.toBeInTheDocument();
    });

    it("offers to send the pending invite again instead", async () => {
        const backend = new InvitesBackend();
        backend.createReply = () => error(409, "INVITE_ALREADY_PENDING");
        const rendered = await renderInvites({ backend });

        const dialog = await invite(rendered, "Lucy@Wambui.dev");
        await rendered.user.click(await within(dialog).findByRole("button", { name: "Send it again" }));

        expect(await screen.findByText(`Invite sent again to ${LUCY.email}`)).toBeInTheDocument();
        expect(backend.resent).toEqual([LUCY.id]);
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });

    it("stops offering to resend once the address changes", async () => {
        const backend = new InvitesBackend();
        backend.createReply = () => error(409, "INVITE_ALREADY_PENDING");
        const rendered = await renderInvites({ backend });

        const dialog = await invite(rendered, LUCY.email);
        await within(dialog).findByRole("button", { name: "Send it again" });
        await rendered.user.type(within(dialog).getByRole("textbox", { name: "Email address" }), "x");

        expect(within(dialog).queryByRole("button", { name: "Send it again" })).not.toBeInTheDocument();
    });

    it("explains a resend refused by the cooldown, and stays open", async () => {
        const backend = new InvitesBackend();
        backend.createReply = () => error(409, "INVITE_ALREADY_PENDING");
        backend.resendReply = () =>
            error(429, "TOO_MANY_REQUESTS", {
                message: "This invitation was sent recently. Try again shortly.",
                headers: { "Retry-After": "240" },
            });
        const rendered = await renderInvites({ backend });

        const dialog = await invite(rendered, LUCY.email);
        await rendered.user.click(await within(dialog).findByRole("button", { name: "Send it again" }));

        expect(await within(dialog).findByRole("alert")).toHaveTextContent(
            "This invite was emailed moments ago. You can send it again in 4 min.",
        );
    });

    it("shows the backend's own words when the organization is at its limit", async () => {
        const backend = new InvitesBackend();
        backend.createReply = () =>
            error(409, "QUOTA_EXCEEDED", { message: "This organization has too many pending invitations." });
        const rendered = await renderInvites({ backend });

        const dialog = await invite(rendered, "mercy@kilimalabs.co");

        expect(await within(dialog).findByRole("alert")).toHaveTextContent(
            "This organization has too many pending invitations.",
        );
        expect(within(dialog).getByRole("textbox", { name: "Email address" })).not.toHaveAccessibleDescription();
    });

    it("explains that a personal organization can't have other members", async () => {
        const backend = new InvitesBackend();
        backend.createReply = () => error(409, "PERSONAL_ORG_IMMUTABLE");
        const rendered = await renderInvites({ backend });

        const dialog = await invite(rendered, "mercy@kilimalabs.co");

        expect(await within(dialog).findByRole("alert")).toHaveTextContent(
            "A personal organization can't have other members.",
        );
    });

    it("puts a field error from the backend on its field", async () => {
        const backend = new InvitesBackend();
        backend.createReply = () =>
            error(400, "VALIDATION_FAILED", {
                validationErrors: [{ field: "email", message: "must be a well-formed email address" }],
            });
        const rendered = await renderInvites({ backend });

        const dialog = await invite(rendered, "mercy@kilimalabs.co");

        await waitFor(() => {
            expect(within(dialog).getByRole("textbox", { name: "Email address" })).toHaveAccessibleDescription(
                "must be a well-formed email address",
            );
        });
    });

    it("fills the form in to invite someone again", async () => {
        const rendered = await renderInvites({ searchParams: "?inviteStatus=EXPIRED" });

        await rendered.user.click(screen.getByRole("button", { name: `Invite again: ${OTIENO.email}` }));

        const dialog = await screen.findByRole("dialog", { name: "Invite someone to Kilima Labs" });
        expect(within(dialog).getByRole("textbox", { name: "Email address" })).toHaveValue(OTIENO.email);
        expect(within(dialog).getByRole("radio", { name: /^Developer/ })).toBeChecked();
    });
});
