import { asUserId } from "@/shared/domain/ids";
import { renderWithProviders } from "@/test/render";
import { screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { Member } from "../domain/member";
import { MemberPicker } from "./MemberPicker";
import { AMANI, FATUMA, GRACE, KILIMA, MembersBackend, WANJIRU } from "./testing/render-members";

function renderPicker(excludeUserIds: readonly string[] = []) {
    const backend = new MembersBackend();
    backend.install();
    const onSelect = vi.fn<(member: Member) => void>();
    const rendered = renderWithProviders(
        <MemberPicker
            orgId={KILIMA}
            label="Add a member"
            excludeUserIds={excludeUserIds.map(asUserId)}
            onSelect={onSelect}
        />,
    );
    return { ...rendered, backend, onSelect, input: screen.getByRole("combobox", { name: "Add a member" }) };
}

const suggestions = () =>
    within(screen.getByRole("listbox", { name: "Members" }))
        .queryAllByRole("option")
        .map((option) => within(option).getByText(/@/).textContent);

describe("MemberPicker", () => {
    it("suggests the organization's active members on focus, leaving out the ones excluded", async () => {
        const { user, input, backend } = renderPicker([AMANI.userId, GRACE.userId]);

        expect(input).toHaveAttribute("aria-expanded", "false");
        await user.click(input);

        await waitFor(() => {
            expect(suggestions()).toEqual([WANJIRU.email, FATUMA.email, "kevin.ochieng@gmail.com"]);
        });
        expect(input).toHaveAttribute("aria-expanded", "true");
        expect(Object.fromEntries(backend.listRequests[0] ?? [])).toEqual({
            page: "0",
            size: "10",
            sort: "displayName,asc",
            status: "ACTIVE",
        });
    });

    it("searches the server once typing pauses", async () => {
        const { user, input, backend } = renderPicker();

        await user.type(input, "fat");

        await waitFor(() => {
            expect(suggestions()).toEqual([FATUMA.email]);
        });
        expect(backend.listed("q")).toEqual([null, "fat"]);
    });

    it("picks a member with the keyboard and starts over", async () => {
        const { user, input, onSelect } = renderPicker([AMANI.userId]);

        await user.type(input, "g");
        await waitFor(() => {
            expect(suggestions()).toEqual([GRACE.email]);
        });
        await user.keyboard("{ArrowDown}");
        expect(input).toHaveAttribute("aria-activedescendant", screen.getByRole("option", { name: /Grace Njeri/ }).id);
        await user.keyboard("{Enter}");

        expect(onSelect).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ userId: GRACE.userId }));
        expect(input).toHaveValue("");
        expect(input).toHaveAttribute("aria-expanded", "false");
    });

    it("picks a member with the pointer", async () => {
        const { user, input, onSelect } = renderPicker();

        await user.click(input);
        await user.click(await screen.findByRole("option", { name: /Fatuma Hassan/ }));

        expect(onSelect).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ userId: FATUMA.userId }));
    });

    it("says when nobody matches, and closes on Escape", async () => {
        const { user, input } = renderPicker();

        await user.type(input, "zawadi");

        expect(await screen.findByText("No members match.")).toBeInTheDocument();
        await user.keyboard("{Escape}");
        expect(input).toHaveAttribute("aria-expanded", "false");
    });
});
