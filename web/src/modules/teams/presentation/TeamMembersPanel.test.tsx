import type { Page } from "@/shared/domain/page";
import { error } from "@/test/msw/envelopes";
import { screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Team } from "../domain/team";
import { DEFAULT_TEAM_LIST_PARAMS, teamListQuery } from "../domain/team-list-query";
import { aTeam } from "../domain/testing/fixtures";
import { teamKeys } from "./queries";
import {
    FATUMA,
    GRACE,
    KEVIN,
    KILIMA,
    PAYMENTS,
    PAYMENTS_ID,
    renderTeam,
    TeamsBackend,
    WANJIRU,
} from "./testing/render-teams";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({
    useRouter: () => router,
    usePathname: () => `/orgs/org-kilima/teams/${PAYMENTS.id}`,
}));

type User = Awaited<ReturnType<typeof renderTeam>>["user"];

const people = () =>
    within(screen.getByRole("list", { name: "People" }))
        .getAllByRole("listitem")
        .map((row) => within(row).getByText(/@/).textContent);

/** The line under the team's name: slug, creation date and head count. */
const summary = () => screen.getByRole("heading", { level: 1 }).closest("header")?.textContent;

const listKey = teamKeys.list(KILIMA, teamListQuery(DEFAULT_TEAM_LIST_PARAMS));

/** The grid as the teams page left it, so a change on this page can be seen reaching its card. */
function seedGrid(queryClient: Awaited<ReturnType<typeof renderTeam>>["queryClient"], memberCount = 2) {
    queryClient.setQueryData<Page<Team>>(listKey, {
        items: [aTeam({ id: PAYMENTS_ID, memberCount }), aTeam({ name: "Web", memberCount: 0 })],
        page: 0,
        size: 24,
        totalItems: 2,
        totalPages: 1,
        isFirst: true,
        isLast: true,
    });
}

const gridCount = (queryClient: Awaited<ReturnType<typeof renderTeam>>["queryClient"]) =>
    queryClient.getQueryData<Page<Team>>(listKey)?.items.find((team) => team.id === PAYMENTS_ID)?.memberCount;

async function openAddPeople(user: User) {
    await user.click(screen.getByRole("button", { name: "Add people" }));
    return screen.findByRole("dialog", { name: "Add people to Payments" });
}

async function pick(user: User, dialog: HTMLElement, name: RegExp) {
    await user.click(within(dialog).getByRole("combobox", { name: "Member" }));
    await user.click(await within(dialog).findByRole("option", { name }));
}

const suggestions = (dialog: HTMLElement) =>
    within(within(dialog).getByRole("listbox", { name: "Members" }))
        .queryAllByRole("option")
        .map((option) => within(option).getByText(/@/).textContent);

beforeEach(() => {
    vi.clearAllMocks();
});

describe("TeamMembersPanel", () => {
    it("lists the team's people with their organization roles, and says a team grants nothing", async () => {
        await renderTeam();

        expect(people()).toEqual([GRACE.email, WANJIRU.email]);
        expect(screen.getByRole("heading", { level: 1, name: "Payments" })).toBeInTheDocument();
        expect(summary()).toContain("2 people");
        expect(
            screen.getByText("Roles come from the organization. A team doesn't change what someone can do."),
        ).toBeInTheDocument();
    });

    it.each([
        ["a developer", FATUMA],
        ["a viewer", KEVIN],
    ])("offers %s no way to change who is on the team", async (_, me) => {
        await renderTeam({ backend: new TeamsBackend(undefined, undefined, undefined, me) });

        expect(people()).toEqual([GRACE.email, WANJIRU.email]);
        expect(screen.queryByRole("button", { name: "Add people" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: /^Remove / })).not.toBeInTheDocument();
        expect(screen.queryByRole("heading", { name: "Team name" })).not.toBeInTheDocument();
        expect(screen.queryByRole("heading", { name: "Delete team" })).not.toBeInTheDocument();
    });

    it("leaves the team's current people out of the picker", async () => {
        const { user } = await renderTeam();

        const dialog = await openAddPeople(user);
        await user.click(within(dialog).getByRole("combobox", { name: "Member" }));

        await waitFor(() => {
            expect(suggestions(dialog)).toEqual(["amani@kilimalabs.co", FATUMA.email, KEVIN.email]);
        });
    });

    it("adds someone, moving the counts on this page and on the grid without reading the grid again", async () => {
        const { user, backend, queryClient } = await renderTeam();
        seedGrid(queryClient);

        const dialog = await openAddPeople(user);
        await pick(user, dialog, /Fatuma Hassan/);
        await user.click(within(dialog).getByRole("button", { name: "Add to team" }));

        expect(await screen.findByText("Fatuma Hassan added to Payments")).toBeInTheDocument();
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        expect(backend.sent("add").map(({ body }) => body)).toEqual([{ userId: FATUMA.userId }]);
        await waitFor(() => {
            expect(people()).toEqual([FATUMA.email, GRACE.email, WANJIRU.email]);
        });
        expect(summary()).toContain("3 people");
        expect(gridCount(queryClient)).toBe(3);
        expect(queryClient.getQueryState(listKey)?.isInvalidated).toBe(false);
        expect(backend.sent("get")).toHaveLength(1);
    });

    it("reads everything again quietly when the person was already added elsewhere", async () => {
        const { user, backend, queryClient } = await renderTeam();
        seedGrid(queryClient);

        const dialog = await openAddPeople(user);
        await pick(user, dialog, /Fatuma Hassan/);
        backend.rosters.set(PAYMENTS.id, [...backend.roster(PAYMENTS.id), FATUMA]);
        await user.click(within(dialog).getByRole("button", { name: "Add to team" }));

        expect(await screen.findByText("Fatuma Hassan is already on Payments")).toBeInTheDocument();
        expect(screen.queryByRole("alert")).not.toBeInTheDocument();
        await waitFor(() => {
            expect(people()).toEqual([FATUMA.email, GRACE.email, WANJIRU.email]);
        });
        await waitFor(() => {
            expect(summary()).toContain("3 people");
        });
        expect(backend.sent("get")).toHaveLength(2);
        expect(queryClient.getQueryState(listKey)?.isInvalidated).toBe(true);
    });

    it("explains when the person left the organization meanwhile, and lets another be picked", async () => {
        const { user, backend } = await renderTeam();

        const dialog = await openAddPeople(user);
        await pick(user, dialog, /Kevin Ochieng/);
        backend.people = backend.people.filter((person) => person.userId !== KEVIN.userId);
        await user.click(within(dialog).getByRole("button", { name: "Add to team" }));

        expect(await within(dialog).findByRole("alert")).toHaveTextContent(
            "Kevin Ochieng is no longer a member of Kilima Labs, so they can't join a team.",
        );
        expect(within(dialog).getByRole("combobox", { name: "Member" })).toBeInTheDocument();
        expect(within(dialog).getByRole("button", { name: "Add to team" })).toBeDisabled();
        await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
        expect(summary()).toContain("2 people");
    });

    it("takes someone off the team at once, with the counts", async () => {
        const { user, backend, queryClient } = await renderTeam();
        seedGrid(queryClient);

        await user.click(screen.getByRole("button", { name: "Remove Grace Njeri from Payments" }));
        const dialog = await screen.findByRole("dialog", { name: "Remove Grace Njeri from Payments?" });
        expect(dialog).toHaveTextContent("Grace stays in Kilima Labs with the same role");
        await user.click(within(dialog).getByRole("button", { name: "Remove from team" }));

        expect(people()).toEqual([WANJIRU.email]);
        expect(summary()).toContain("1 person");
        expect(gridCount(queryClient)).toBe(1);
        expect(await screen.findByText("Grace Njeri removed from Payments")).toBeInTheDocument();
        expect(backend.sent("remove").map(({ body }) => body)).toEqual([{ userId: GRACE.userId }]);
        expect(queryClient.getQueryState(listKey)?.isInvalidated).toBe(false);
    });

    it("puts the person and the counts back when the removal is refused", async () => {
        const backend = new TeamsBackend();
        backend.replies.set("remove", () => error(403, "INSUFFICIENT_ROLE", { message: "Insufficient role" }));
        const { user, queryClient } = await renderTeam({ backend });
        seedGrid(queryClient);

        await user.click(screen.getByRole("button", { name: "Remove Grace Njeri from Payments" }));
        await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Remove from team" }));

        expect(await screen.findByText("Only admins can take Grace Njeri off a team.")).toBeInTheDocument();
        expect(people()).toEqual([GRACE.email, WANJIRU.email]);
        expect(summary()).toContain("2 people");
        expect(gridCount(queryClient)).toBe(2);
    });

    it("draws the empty state with a way to add people for an admin", async () => {
        const { user } = await renderTeam({ backend: new TeamsBackend(undefined, new Map()) });

        const empty = screen.getByRole("heading", { name: "No one's on this team yet" }).parentElement;
        await user.click(within(empty ?? document.body).getByRole("button", { name: "Add people" }));

        expect(await screen.findByRole("dialog", { name: "Add people to Payments" })).toBeInTheDocument();
    });
});
