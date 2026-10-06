import { error, ok } from "@/test/msw/envelopes";
import { server, sessionApiUrl } from "@/test/msw/server";
import { screen, waitFor, within } from "@testing-library/react";
import { http } from "msw";
import { describe, expect, it, vi } from "vitest";
import { AMANI, MembersBackend, memberUrl, renderMembers, WANJIRU } from "./testing/render-members";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router, usePathname: () => "/orgs/org-kilima/members" }));

const reauthenticationRequired = () =>
    error(403, "REAUTHENTICATION_REQUIRED", { message: "This action needs a recent sign-in." });

/** Answers each transfer in turn; a successful one changes who owns the organization, as the backend does. */
function replyToTransfers(backend: MembersBackend, ...replies: (() => Response)[]) {
    const transfers: string[] = [];
    server.use(
        http.post(`${memberUrl(WANJIRU.userId)}/transfer-ownership`, () => {
            transfers.push(WANJIRU.userId);
            const reply = replies[transfers.length - 1] ?? replies.at(-1);
            const response = reply?.() ?? ok({ ...WANJIRU, role: "OWNER" });
            if (response.ok) {
                backend.me = { ...AMANI, role: "ADMIN" };
                backend.replace(backend.me);
                backend.replace({ ...WANJIRU, role: "OWNER" });
            }
            return response;
        }),
    );
    return transfers;
}

function replyToReauthentication() {
    const passwords: unknown[] = [];
    server.use(
        http.post(sessionApiUrl("/reauthenticate"), async ({ request }) => {
            passwords.push(await request.json());
            return ok({ ok: true }, "Reauthenticated");
        }),
    );
    return passwords;
}

async function confirmTransfer(user: Awaited<ReturnType<typeof renderMembers>>["user"]) {
    await user.click(screen.getByRole("button", { name: "Actions for Wanjiru Kamau" }));
    await user.click(await screen.findByRole("menuitem", { name: "Make owner" }));
    const dialog = await screen.findByRole("dialog", { name: "Make Wanjiru Kamau the owner?" });
    expect(dialog).toHaveTextContent("You'll become an admin, and only Wanjiru can give ownership back.");
    await user.click(within(dialog).getByRole("button", { name: "Transfer ownership" }));
    return dialog;
}

describe("TransferOwnershipDialog", () => {
    it("asks for the password when the backend does, then hands over ownership and shrinks the caller's controls", async () => {
        const backend = new MembersBackend();
        const transfers = replyToTransfers(backend, reauthenticationRequired, () => ok({ ...WANJIRU, role: "OWNER" }));
        const passwords = replyToReauthentication();
        const { user } = await renderMembers({ backend });

        await confirmTransfer(user);
        const prompt = await screen.findByRole("dialog", { name: "Confirm it's you" });
        await user.type(within(prompt).getByLabelText("Your password"), "correct horse{Enter}");

        expect(await screen.findByText("Wanjiru Kamau now owns Kilima Labs")).toBeInTheDocument();
        expect(transfers).toHaveLength(2);
        expect(passwords).toEqual([{ password: "correct horse" }]);
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        await waitFor(() => {
            expect(within(screen.getByRole("row", { name: /Wanjiru Kamau/ })).getByText("Owner")).toBeInTheDocument();
        });
        expect(within(screen.getByRole("row", { name: /Amani Otieno/ })).getByText("Admin")).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Actions for Wanjiru Kamau" })).not.toBeInTheDocument();
        expect(screen.queryByText(/has one owner/)).not.toBeInTheDocument();

        await user.click(screen.getByRole("button", { name: "Actions for Kevin Ochieng" }));
        expect(await screen.findByRole("menuitem", { name: "Remove from Kilima Labs" })).toBeInTheDocument();
        expect(screen.queryByRole("menuitem", { name: "Make owner" })).not.toBeInTheDocument();
        expect(screen.queryByRole("menuitem", { name: "Change role" })).not.toBeInTheDocument();
    });

    it("hands over ownership without a prompt while the sign-in is recent", async () => {
        const backend = new MembersBackend();
        const transfers = replyToTransfers(backend);
        const passwords = replyToReauthentication();
        const { user } = await renderMembers({ backend });

        await confirmTransfer(user);

        expect(await screen.findByText("Wanjiru Kamau now owns Kilima Labs")).toBeInTheDocument();
        expect(transfers).toHaveLength(1);
        expect(passwords).toEqual([]);
    });

    it("keeps the dialog open with the reason when the password prompt is cancelled", async () => {
        const backend = new MembersBackend();
        const transfers = replyToTransfers(backend, reauthenticationRequired);
        const { user } = await renderMembers({ backend });

        const dialog = await confirmTransfer(user);
        const prompt = await screen.findByRole("dialog", { name: "Confirm it's you" });
        await user.click(within(prompt).getByRole("button", { name: "Cancel" }));

        expect(await within(dialog).findByRole("alert")).toHaveTextContent(
            "Confirm your password to transfer ownership.",
        );
        expect(transfers).toHaveLength(1);
        await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
        expect(within(screen.getByRole("row", { name: /Amani Otieno/ })).getByText("Owner")).toBeInTheDocument();
    });

    it("explains a transfer to someone who can no longer take it", async () => {
        const backend = new MembersBackend();
        replyToTransfers(backend, () =>
            error(409, "INVALID_ROLE_TRANSITION", { message: "That role transition is not allowed." }),
        );
        const { user } = await renderMembers({ backend });

        const dialog = await confirmTransfer(user);

        expect(await within(dialog).findByRole("alert")).toHaveTextContent(
            "Wanjiru Kamau can't become the owner. They may have left, or already own Kilima Labs.",
        );
    });
});
