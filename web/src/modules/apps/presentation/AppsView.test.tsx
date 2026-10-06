import { error } from "@/test/msw/envelopes";
import { screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AppsBackend, CHECKOUT, DOCS, KEVIN, MERCHANT, PAYMENTS, renderApps, WEB } from "./testing/render-apps";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router, usePathname: () => "/orgs/org-kilima/apps" }));

const appNames = () =>
    within(screen.getByRole("table", { name: "App" }))
        .getAllByRole("row")
        .slice(1)
        .map((row) => within(row).getAllByRole("cell")[0]?.textContent);

beforeEach(() => {
    vi.clearAllMocks();
});

describe("AppsView", () => {
    it("lists the newest apps first, with where each runs, its team and no repository yet", async () => {
        await renderApps();

        expect(appNames()).toEqual(["Docsdocs", "Merchant dashboardmerchant-dashboard", "Checkout APIcheckout-api"]);
        const checkout = screen.getByRole("row", { name: /Checkout API/ });
        expect(within(checkout).getByRole("link", { name: /Checkout API/ })).toHaveAttribute(
            "href",
            `/orgs/org-kilima/apps/${CHECKOUT.id}`,
        );
        expect(checkout).toHaveTextContent("AWS · af-south-1");
        expect(within(checkout).getByTitle("Cape Town (af-south-1)")).toBeInTheDocument();
        expect(checkout).toHaveTextContent("Payments");
        expect(checkout).toHaveTextContent("No repository");
        expect(screen.getByRole("row", { name: /Docs/ })).toHaveTextContent("No team");
        expect(screen.getByText("3 apps")).toBeInTheDocument();
    });

    it("filters by team and by cloud through the URL, back on the first page", async () => {
        const urls: string[] = [];
        const { user, backend } = await renderApps({
            searchParams: "?page=1",
            onUrlUpdate: ({ queryString }) => {
                urls.push(queryString);
            },
        });

        await user.selectOptions(screen.getByRole("combobox", { name: "Team" }), "Web");
        await waitFor(() => {
            expect(appNames()).toEqual(["Merchant dashboardmerchant-dashboard"]);
        });
        expect(urls.at(-1)).toBe(`?team=${WEB.id}`);

        await user.selectOptions(screen.getByRole("combobox", { name: "Team" }), "All teams");
        await user.selectOptions(screen.getByRole("combobox", { name: "Cloud" }), "Google Cloud");
        await waitFor(() => {
            expect(appNames()).toEqual(["Docsdocs", "Merchant dashboardmerchant-dashboard"]);
        });
        expect(urls.at(-1)).toBe("?cloud=GCP");
        expect(backend.sent("list").map(({ params }) => [params?.get("teamId"), params?.get("cloudProvider")])).toEqual(
            [
                [null, null],
                [WEB.id, null],
                [null, "GCP"],
            ],
        );
    });

    it("searches name and slug once typing pauses, keeping the search in the URL", async () => {
        const urls: string[] = [];
        const { user, backend } = await renderApps({
            onUrlUpdate: ({ queryString }) => {
                urls.push(queryString);
            },
        });

        await user.type(screen.getByRole("searchbox", { name: "Search apps" }), "  dash");

        await waitFor(() => {
            expect(appNames()).toEqual(["Merchant dashboardmerchant-dashboard"]);
        });
        expect(urls.at(-1)).toBe("?q=dash");
        expect(backend.sent("list").map(({ params }) => params?.get("q") ?? null)).toEqual([null, "dash"]);
    });

    it("lists the apps without a team from the No team option", async () => {
        const urls: string[] = [];
        const { user, backend } = await renderApps({
            onUrlUpdate: ({ queryString }) => {
                urls.push(queryString);
            },
        });

        await user.selectOptions(screen.getByRole("combobox", { name: "Team" }), "No team");

        await waitFor(() => {
            expect(appNames()).toEqual(["Docsdocs"]);
        });
        expect(urls.at(-1)).toBe("?team=none");
        expect(Object.fromEntries(backend.sent("list").at(-1)?.params ?? [])).toMatchObject({ unassigned: "true" });
        expect(backend.sent("list").at(-1)?.params?.has("teamId")).toBe(false);
    });

    it("reads the filters and the order from the URL it opens on", async () => {
        const { backend } = await renderApps({ searchParams: `?team=${PAYMENTS.id}&sort=name,asc` });

        expect(appNames()).toEqual(["Checkout APIcheckout-api"]);
        expect(screen.getByRole("combobox", { name: "Team" })).toHaveValue(PAYMENTS.id);
        expect(Object.fromEntries(backend.sent("list")[0]?.params ?? [])).toMatchObject({
            teamId: PAYMENTS.id,
            sort: "name,asc",
        });
    });

    it("reorders the list from the sort", async () => {
        const { user } = await renderApps();

        await user.selectOptions(screen.getByRole("combobox", { name: "Sort" }), "Name, A to Z");

        await waitFor(() => {
            expect(appNames()).toEqual([
                "Checkout APIcheckout-api",
                "Docsdocs",
                "Merchant dashboardmerchant-dashboard",
            ]);
        });
        expect(screen.getByRole("columnheader", { name: "App" })).toHaveAttribute("aria-sort", "ascending");
    });

    it("says nothing matches, and clears the filters, when a filter leaves no apps", async () => {
        const { user } = await renderApps({ backend: new AppsBackend([DOCS, MERCHANT]), searchParams: "?cloud=AWS" });

        expect(screen.getByRole("heading", { name: "No apps match these filters" })).toBeInTheDocument();
        await user.click(screen.getByRole("button", { name: "Clear filters" }));

        await waitFor(() => {
            expect(appNames()).toHaveLength(2);
        });
    });

    it("clears a search along with the other filters, emptying the search box", async () => {
        const { user } = await renderApps({ searchParams: "?q=nothing-like-this&team=none" });

        expect(screen.getByRole("heading", { name: "No apps match these filters" })).toBeInTheDocument();
        expect(screen.getByRole("searchbox", { name: "Search apps" })).toHaveValue("nothing-like-this");
        await user.click(screen.getByRole("button", { name: "Clear filters" }));

        await waitFor(() => {
            expect(appNames()).toHaveLength(3);
        });
        expect(screen.getByRole("searchbox", { name: "Search apps" })).toHaveValue("");
        expect(screen.getByRole("combobox", { name: "Team" })).toHaveValue("");
    });

    it("draws the empty state with a way to start for a developer and above", async () => {
        await renderApps({ backend: new AppsBackend([]) });

        expect(screen.getByRole("heading", { name: "No apps yet" })).toBeInTheDocument();
        expect(screen.getAllByRole("button", { name: "New app" })).toHaveLength(1);
        expect(screen.queryByRole("search", { name: "Filter apps" })).not.toBeInTheDocument();
    });

    it("draws the empty state without one for a viewer", async () => {
        await renderApps({ backend: new AppsBackend([], undefined, KEVIN) });

        expect(screen.getByText("A developer or above can create the first one.")).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "New app" })).not.toBeInTheDocument();
    });

    it("draws the error state with a retry when the list can't be read", async () => {
        const backend = new AppsBackend();
        backend.replies.set("list", () => error(503, "SERVICE_UNAVAILABLE"));
        const { user } = await renderApps({ backend });

        expect(await screen.findByText(/temporarily unavailable/)).toBeInTheDocument();
        await user.click(screen.getByRole("button", { name: /Try again/ }));

        expect(await screen.findByRole("table", { name: "App" })).toBeInTheDocument();
    });
});
