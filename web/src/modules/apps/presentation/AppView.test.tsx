import { screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { CHECKOUT, DOCS, PAYMENTS, renderApp } from "./testing/render-apps";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({
    useRouter: () => router,
    usePathname: () => `/orgs/org-kilima/apps/${CHECKOUT.id}`,
}));

function head(): HTMLElement {
    const header = screen.getByRole("heading", { level: 1 }).closest("header");
    if (header === null) throw new Error("The page has no header");
    return header;
}

beforeEach(() => {
    vi.clearAllMocks();
});

describe("AppView", () => {
    it("heads the page with the app's slug, where it runs and a link to its team", async () => {
        await renderApp();

        expect(within(head()).getByRole("heading", { name: "Checkout API" })).toBeInTheDocument();
        expect(head()).toHaveTextContent("checkout-api");
        expect(head()).toHaveTextContent("AWS · af-south-1");
        expect(within(head()).getByRole("link", { name: "Payments" })).toHaveAttribute(
            "href",
            `/orgs/org-kilima/teams/${PAYMENTS.id}`,
        );
    });

    it("says when an app has no team", async () => {
        await renderApp({ appId: DOCS.id });

        expect(head()).toHaveTextContent("No team");
        expect(within(head()).queryByRole("link")).not.toBeInTheDocument();
    });

    it("shows the settings alone until a repository section is handed in", async () => {
        await renderApp();

        expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
        expect(screen.getByRole("region", { name: "General" })).toBeInTheDocument();
    });

    it("puts a handed-in repository section and actions in their slots, settings on a tab of their own", async () => {
        const { user } = await renderApp({
            slots: { repository: <p>Repository section</p>, actions: <button type="button">Start a build</button> },
        });

        expect(within(head()).getByRole("button", { name: "Start a build" })).toBeInTheDocument();
        expect(screen.getByRole("tab", { name: "Repository", selected: true })).toBeInTheDocument();
        expect(screen.getByText("Repository section")).toBeVisible();

        await user.click(screen.getByRole("tab", { name: "Settings" }));

        expect(await screen.findByRole("region", { name: "General" })).toBeInTheDocument();
    });
});
