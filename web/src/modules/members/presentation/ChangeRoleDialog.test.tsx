import { error, ok } from "@/test/msw/envelopes";
import { server } from "@/test/msw/server";
import { screen, waitFor, within } from "@testing-library/react";
import { http } from "msw";
import { describe, expect, it, vi } from "vitest";
import { MembersBackend, memberUrl, renderMembers, TEAM, WANJIRU } from "./testing/render-members";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router, usePathname: () => "/orgs/org-kilima/members" }));

const wanjirusRow = () => screen.getByRole("row", { name: /Wanjiru Kamau/ });

/** Holds the PATCH until `answer` is called, so the screen can be checked while the change is in flight. */
function holdRoleChange(backend: MembersBackend) {
    const sent: unknown[] = [];
    let answer: (reply: () => Response) => void = () => undefined;
    const reply = new Promise<() => Response>((resolve) => {
        answer = resolve;
    });
    server.use(
        http.patch(memberUrl(WANJIRU.userId), async ({ request }) => {
            sent.push(await request.json());
            const response = (await reply)();
            if (response.ok) backend.replace({ ...WANJIRU, role: "DEVELOPER" });
            return response;
        }),
    );
    return {
        sent,
        answer: (reply: () => Response) => {
            answer(reply);
        },
    };
}

async function chooseDeveloper(user: Awaited<ReturnType<typeof renderMembers>>["user"]) {
    await user.click(within(wanjirusRow()).getByRole("button", { name: "Actions for Wanjiru Kamau" }));
    await user.click(await screen.findByRole("menuitem", { name: "Change role" }));
    const dialog = await screen.findByRole("dialog", { name: "Change Wanjiru Kamau's role" });
    expect(within(dialog).getByRole("radio", { name: /Admin/ })).toBeChecked();
    await user.click(within(dialog).getByRole("radio", { name: /Developer/ }));
    await user.click(within(dialog).getByRole("button", { name: "Save role" }));
}

describe("ChangeRoleDialog", () => {
    it("shows the new role at once and keeps it when the backend agrees", async () => {
        const backend = new MembersBackend();
        const roleChange = holdRoleChange(backend);
        const { user } = await renderMembers({ backend });

        await chooseDeveloper(user);

        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        expect(within(wanjirusRow()).getByText("Developer")).toBeInTheDocument();
        roleChange.answer(() => ok({ ...WANJIRU, role: "DEVELOPER" }, "Member role updated"));

        expect(await screen.findByText("Wanjiru Kamau is now a developer")).toBeInTheDocument();
        expect(within(wanjirusRow()).getByText("Developer")).toBeInTheDocument();
        expect(roleChange.sent).toEqual([{ role: "DEVELOPER" }]);
    });

    it("puts the old role back and says why when the backend refuses", async () => {
        const backend = new MembersBackend();
        const roleChange = holdRoleChange(backend);
        const { user } = await renderMembers({ backend });

        await chooseDeveloper(user);
        expect(within(wanjirusRow()).getByText("Developer")).toBeInTheDocument();
        const listsBefore = backend.listRequests.length;
        roleChange.answer(() => error(409, "LAST_OWNER", { message: "The organization must keep an owner." }));

        expect(
            await screen.findByText(
                "Wanjiru Kamau is the only owner, so their role can't change. Make someone else the owner first.",
            ),
        ).toBeInTheDocument();
        expect(within(wanjirusRow()).getByText("Admin")).toBeInTheDocument();
        await waitFor(() => {
            expect(backend.listRequests.length).toBeGreaterThan(listsBefore);
        });
    });

    it("sends nothing when the role is saved unchanged", async () => {
        const backend = new MembersBackend();
        const roleChange = holdRoleChange(backend);
        const { user } = await renderMembers({ backend });

        await user.click(within(wanjirusRow()).getByRole("button", { name: "Actions for Wanjiru Kamau" }));
        await user.click(await screen.findByRole("menuitem", { name: "Change role" }));
        await user.click(await screen.findByRole("button", { name: "Save role" }));

        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        expect(roleChange.sent).toEqual([]);
    });

    it("offers the owner no way to change their own role, and an admin no way to change anyone's", async () => {
        const { user, unmount } = await renderMembers();

        expect(screen.queryByRole("button", { name: "Actions for Amani Otieno" })).not.toBeInTheDocument();
        unmount();

        await renderMembers({ backend: new MembersBackend([...TEAM], WANJIRU) });
        await user.click(screen.getByRole("button", { name: "Actions for Kevin Ochieng" }));

        expect(await screen.findByRole("menuitem", { name: "Remove from Kilima Labs" })).toBeInTheDocument();
        expect(screen.queryByRole("menuitem", { name: "Change role" })).not.toBeInTheDocument();
        expect(screen.queryByRole("menuitem", { name: "Make owner" })).not.toBeInTheDocument();
    });
});
