import { error } from "@/test/msw/envelopes";
import { orgDto } from "@/test/msw/org-team";
import { screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AMANI, FATUMA, KEVIN, KILIMA, renderTeams, TeamsBackend, WANJIRU } from "./testing/render-teams";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router, usePathname: () => "/orgs/org-kilima/teams" }));

const cards = () =>
    within(screen.getByRole("list", { name: "Teams" }))
        .getAllByRole("link")
        .map((card) => card.textContent);

async function openNewTeam(user: Awaited<ReturnType<typeof renderTeams>>["user"]) {
    await user.click(screen.getByRole("button", { name: "New team" }));
    return screen.findByRole("dialog", { name: "New team" });
}

beforeEach(() => {
    vi.clearAllMocks();
});

describe("TeamsView", () => {
    it("shows a card per team, by name, with its slug and how many people are on it", async () => {
        await renderTeams();

        expect(cards()).toEqual(["Paymentspayments2 people", "Webweb0 people"]);
        expect(screen.getByRole("link", { name: /Payments/ })).toHaveAttribute(
            "href",
            "/orgs/org-kilima/teams/3f2b8c1e-5a4d-4e6f-9b7a-1c2d3e4f5a6b",
        );
    });

    it("reorders the grid from the URL's sort", async () => {
        const urls: string[] = [];
        const { user, backend } = await renderTeams({
            onUrlUpdate: ({ queryString }) => {
                urls.push(queryString);
            },
        });

        await user.selectOptions(screen.getByRole("combobox", { name: "Sort" }), "Newest first");

        await waitFor(() => {
            expect(cards()).toEqual(["Webweb0 people", "Paymentspayments2 people"]);
        });
        expect(urls.at(-1)).toBe("?sort=createdAt,desc");
        expect(backend.sent("list").map(({ params }) => params?.get("sort"))).toEqual(["name,asc", "createdAt,desc"]);
    });

    it.each([
        ["an admin", WANJIRU],
        ["the owner", AMANI],
    ])("lets %s create a team", async (_, me) => {
        await renderTeams({ backend: new TeamsBackend(undefined, undefined, undefined, me) });

        expect(screen.getByRole("button", { name: "New team" })).toBeInTheDocument();
    });

    it.each([
        ["a developer", FATUMA],
        ["a viewer", KEVIN],
    ])("offers %s no way to create a team", async (_, me) => {
        await renderTeams({ backend: new TeamsBackend(undefined, undefined, undefined, me) });

        expect(screen.getByRole("list", { name: "Teams" })).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "New team" })).not.toBeInTheDocument();
    });

    it("draws the empty state with a way to start for an admin", async () => {
        const { user } = await renderTeams({ backend: new TeamsBackend([]) });

        expect(screen.getByRole("heading", { name: "No teams yet" })).toBeInTheDocument();
        expect(screen.getAllByRole("button", { name: "New team" })).toHaveLength(1);
        await openNewTeam(user);
    });

    it("draws the empty state without one for a viewer", async () => {
        await renderTeams({ backend: new TeamsBackend([], undefined, undefined, KEVIN) });

        expect(screen.getByRole("heading", { name: "No teams yet" })).toBeInTheDocument();
        expect(screen.getByText(/An admin can create the first one/)).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "New team" })).not.toBeInTheDocument();
    });

    it("creates a team with a slug that follows its name, then opens it", async () => {
        const { user, backend, queryClient } = await renderTeams();
        queryClient.setQueryData(["org", KILIMA, "organization"], orgDto());

        const dialog = await openNewTeam(user);
        await user.type(within(dialog).getByLabelText("Name"), "Data Platform");
        expect(within(dialog).getByLabelText("Slug")).toHaveValue("data-platform");
        await user.click(within(dialog).getByRole("button", { name: "Create team" }));

        await waitFor(() => {
            expect(router.push).toHaveBeenCalledExactlyOnceWith(
                "/orgs/org-kilima/teams/0d0c1a2b-3c4d-4e5f-8a6b-000000000001",
            );
        });
        expect(backend.sent("create").map(({ body }) => body)).toEqual([
            { name: "Data Platform", slug: "data-platform" },
        ]);
        expect(await screen.findByText("Data Platform team created")).toBeInTheDocument();
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
        expect(queryClient.getQueryState(["org", KILIMA, "organization"])?.isInvalidated).toBe(true);
        await waitFor(() => {
            expect(cards()).toContain("Data Platformdata-platform0 people");
        });
    });

    it("puts a taken slug on the slug field and stays open", async () => {
        const backend = new TeamsBackend();
        backend.replies.set("create", () => error(409, "SLUG_TAKEN", { message: "That slug is already in use." }));
        const { user } = await renderTeams({ backend });

        const dialog = await openNewTeam(user);
        await user.type(within(dialog).getByLabelText("Name"), "Payments");
        await user.click(within(dialog).getByRole("button", { name: "Create team" }));

        expect(
            await within(dialog).findByText("Another team already uses that slug. Try another."),
        ).toBeInTheDocument();
        expect(within(dialog).getByLabelText("Slug")).toHaveAttribute("aria-invalid", "true");
        expect(within(dialog).getByLabelText("Slug")).toHaveFocus();
        expect(router.push).not.toHaveBeenCalled();
    });

    it("shows the backend's own words when the organization is out of teams", async () => {
        const backend = new TeamsBackend();
        backend.replies.set("create", () =>
            error(409, "QUOTA_EXCEEDED", { message: "This organization has reached its team limit." }),
        );
        const { user } = await renderTeams({ backend });

        const dialog = await openNewTeam(user);
        await user.type(within(dialog).getByLabelText("Name"), "Data");
        await user.click(within(dialog).getByRole("button", { name: "Create team" }));

        expect(await within(dialog).findByRole("alert")).toHaveTextContent(
            "This organization has reached its team limit.",
        );
    });

    it("checks the name and slug before asking the backend", async () => {
        const { user, backend } = await renderTeams();

        const dialog = await openNewTeam(user);
        await user.type(within(dialog).getByLabelText("Slug"), "Not A Slug");
        await user.click(within(dialog).getByRole("button", { name: "Create team" }));

        expect(await within(dialog).findByText("Enter a name for the team")).toBeInTheDocument();
        expect(
            within(dialog).getByText("Use lowercase letters, numbers and single hyphens, with no hyphen at either end"),
        ).toBeInTheDocument();
        expect(backend.sent("create")).toEqual([]);
    });

    it("explains a failed read and reads again on request", async () => {
        const backend = new TeamsBackend();
        backend.replies.set("list", () => error(503, "SERVICE_UNAVAILABLE"));
        const { user } = await renderTeams({ backend });

        expect(await screen.findByRole("alert")).toHaveTextContent("Pallet is temporarily unavailable.");
        await user.click(screen.getByRole("button", { name: "Try again" }));

        expect(await screen.findByRole("list", { name: "Teams" })).toBeInTheDocument();
    });
});
