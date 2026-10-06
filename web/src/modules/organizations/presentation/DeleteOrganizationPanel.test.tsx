import { error, ok } from "@/test/msw/envelopes";
import { server, sessionApiUrl } from "@/test/msw/server";
import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { clearedLastOrganizationCookie, lastOrganizationCookie, rememberedOrganization } from "./last-organization";
import { KILIMA, ORG_URL, renderOrgSettings } from "./testing/render-org-settings";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router, usePathname: () => "/orgs/org-kilima/settings" }));

const REAUTHENTICATE_URL = sessionApiUrl("/reauthenticate");

function replyToDeletes(...replies: (() => Response)[]) {
    const confirmations: (string | null)[] = [];
    server.use(
        http.delete(ORG_URL, ({ request }) => {
            confirmations.push(request.headers.get("X-Confirm-Slug"));
            const reply = replies[confirmations.length - 1] ?? replies.at(-1);
            return reply?.() ?? new HttpResponse(null, { status: 204 });
        }),
    );
    return confirmations;
}

function replyToReauthentication(reply: () => Response) {
    const passwords: unknown[] = [];
    server.use(
        http.post(REAUTHENTICATE_URL, async ({ request }) => {
            passwords.push(await request.json());
            return reply();
        }),
    );
    return passwords;
}

const reauthenticationRequired = () =>
    error(403, "REAUTHENTICATION_REQUIRED", { message: "This action needs a recent sign-in." });
const deleted = () => new HttpResponse(null, { status: 204 });

async function confirmDeletion(user: Awaited<ReturnType<typeof renderOrgSettings>>["user"]) {
    await user.click(await screen.findByRole("button", { name: "Delete Kilima Labs" }));
    const dialog = await screen.findByRole("dialog", { name: "Delete Kilima Labs?" });
    await user.type(within(dialog).getByLabelText(/to confirm/), "kilima-labs");
    await user.click(within(dialog).getByRole("button", { name: "Delete organization" }));
    return dialog;
}

beforeEach(() => {
    vi.clearAllMocks();
});

afterEach(() => {
    document.cookie = clearedLastOrganizationCookie(false);
});

describe("DeleteOrganizationPanel", () => {
    it("is offered to the owner of a team organization, with what deletion costs", async () => {
        const { user } = await renderOrgSettings();

        await user.click(await screen.findByRole("button", { name: "Delete Kilima Labs" }));

        const dialog = await screen.findByRole("dialog", { name: "Delete Kilima Labs?" });
        expect(dialog).toHaveTextContent("6 members lose access, and 4 apps stop building. You can't undo this.");
        expect(within(dialog).getByRole("button", { name: "Delete organization" })).toBeDisabled();
    });

    it.each([
        ["a personal organization, even to its owner", { role: "OWNER", organization: { kind: "PERSONAL" } }],
        ["an admin who isn't the owner", { role: "ADMIN", organization: {} }],
    ] as const)("is absent for %s", async (_, scenario) => {
        await renderOrgSettings(scenario);

        expect(await screen.findByRole("button", { name: "Save" })).toBeInTheDocument();
        expect(screen.queryByRole("heading", { name: "Delete organization" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: /^Delete / })).not.toBeInTheDocument();
    });

    it("asks for the password only when the backend does, then deletes and forgets the organization", async () => {
        document.cookie = lastOrganizationCookie("org-kilima", false);
        const confirmations = replyToDeletes(reauthenticationRequired, deleted);
        const passwords = replyToReauthentication(() => ok({ ok: true }, "Reauthenticated"));
        const { user, queryClient } = await renderOrgSettings();

        await confirmDeletion(user);

        const prompt = await screen.findByRole("dialog", { name: "Confirm it's you" });
        expect(prompt).toHaveTextContent("Enter the password for amani@kilimalabs.co to continue.");
        expect(within(prompt).getByLabelText("Your password")).toHaveFocus();
        await user.keyboard("correct horse{Enter}");

        await waitFor(() => {
            expect(router.replace).toHaveBeenCalledExactlyOnceWith("/orgs");
        });
        expect(passwords).toEqual([{ password: "correct horse" }]);
        expect(confirmations).toEqual(["kilima-labs", "kilima-labs"]);
        expect(await screen.findByText("Kilima Labs deleted")).toBeInTheDocument();
        expect(queryClient.getQueryCache().findAll({ queryKey: ["org", KILIMA] })).toEqual([]);
        expect(rememberedOrganization(document.cookie)).toBeUndefined();
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });

    it("deletes at once, without a prompt, while the sign-in is recent", async () => {
        const confirmations = replyToDeletes(deleted);
        const passwords = replyToReauthentication(() => ok({ ok: true }));
        const { user } = await renderOrgSettings();

        await confirmDeletion(user);

        await waitFor(() => {
            expect(router.replace).toHaveBeenCalledWith("/orgs");
        });
        expect(confirmations).toEqual(["kilima-labs"]);
        expect(passwords).toEqual([]);
    });

    it("shows the original error on the dialog when the prompt is cancelled", async () => {
        const confirmations = replyToDeletes(reauthenticationRequired);
        const { user, queryClient } = await renderOrgSettings();

        const dialog = await confirmDeletion(user);
        const prompt = await screen.findByRole("dialog", { name: "Confirm it's you" });
        await user.click(within(prompt).getByRole("button", { name: "Cancel" }));

        expect(await within(dialog).findByRole("alert")).toHaveTextContent(
            "Confirm your password to delete this organization.",
        );
        expect(screen.queryByRole("dialog", { name: "Confirm it's you" })).not.toBeInTheDocument();
        expect(confirmations).toEqual(["kilima-labs"]);
        expect(router.replace).not.toHaveBeenCalled();
        expect(queryClient.getQueryData(["org", KILIMA, "organization"])).toBeDefined();
    });

    it("keeps the prompt open with a field error for a wrong password", async () => {
        const confirmations = replyToDeletes(reauthenticationRequired, deleted);
        replyToReauthentication(() => error(401, "INVALID_CREDENTIALS", { message: "Invalid email or password" }));
        const { user } = await renderOrgSettings();

        await confirmDeletion(user);
        const prompt = await screen.findByRole("dialog", { name: "Confirm it's you" });
        await user.type(within(prompt).getByLabelText("Your password"), "wrong{Enter}");

        expect(await within(prompt).findByText("That password isn't right.")).toBeInTheDocument();
        expect(within(prompt).getByLabelText("Your password")).toHaveAttribute("aria-invalid", "true");
        expect(screen.getByRole("dialog", { name: "Confirm it's you" })).toBeInTheDocument();
        expect(confirmations).toEqual(["kilima-labs"]);
    });

    it("shows the backend's confirmation mismatch on the dialog", async () => {
        replyToDeletes(() => error(400, "CONFIRMATION_MISMATCH", { message: "Confirmation does not match." }));
        const { user } = await renderOrgSettings();

        const dialog = await confirmDeletion(user);

        expect(await within(dialog).findByRole("alert")).toHaveTextContent(
            "That doesn't match the organization's URL. Type it exactly as shown.",
        );
        expect(router.replace).not.toHaveBeenCalled();
    });
});
