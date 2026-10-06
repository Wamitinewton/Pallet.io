import { error } from "@/test/msw/envelopes";
import { screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AppsBackend, CHECKOUT, DOCS, FATUMA, KEVIN, PAYMENTS, renderApp, WEB } from "./testing/render-apps";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({
    useRouter: () => router,
    usePathname: () => `/orgs/org-kilima/apps/${CHECKOUT.id}`,
}));

const general = () => screen.getByRole("region", { name: "General" });
const teamSelect = () => within(general()).getByRole("combobox", { name: "Team" });
const save = () => within(general()).getByRole("button", { name: "Save" });

beforeEach(() => {
    vi.clearAllMocks();
});

describe("AppSettingsPanel", () => {
    it("starts from the app's name and team, with nothing to save", async () => {
        await renderApp();

        expect(within(general()).getByLabelText("App name")).toHaveValue("Checkout API");
        expect(teamSelect()).toHaveValue(PAYMENTS.id);
        expect(save()).toBeDisabled();
    });

    it("detaches the app from its team with exactly { teamId: null }", async () => {
        const { user, backend } = await renderApp();

        await user.selectOptions(teamSelect(), "No team");
        await user.click(save());

        expect(await screen.findByText("App updated")).toBeInTheDocument();
        expect(backend.sent("update").map(({ raw }) => raw)).toEqual(['{"teamId":null}']);
        const meta = screen.getByRole("heading", { level: 1 }).closest("header");
        expect(meta).toHaveTextContent("No team");
        expect(save()).toBeDisabled();
    });

    it("renames alone without sending a teamId", async () => {
        const { user, backend } = await renderApp();

        const name = within(general()).getByLabelText("App name");
        await user.clear(name);
        await user.type(name, "Checkout");
        await user.click(save());

        expect(await screen.findByRole("heading", { level: 1, name: "Checkout" })).toBeInTheDocument();
        expect(backend.sent("update").map(({ raw }) => raw)).toEqual(['{"name":"Checkout"}']);
    });

    it("moves an app without a team to one", async () => {
        const { user, backend } = await renderApp({ appId: DOCS.id });

        expect(teamSelect()).toHaveValue("");
        await user.selectOptions(teamSelect(), "Web");
        await user.click(save());

        await waitFor(() => {
            expect(backend.sent("update").map(({ raw }) => raw)).toEqual([`{"teamId":"${WEB.id}"}`]);
        });
    });

    it("reloads the app on CONCURRENT_MODIFICATION and asks for the change again", async () => {
        const backend = new AppsBackend();
        const { user } = await renderApp({ backend });
        backend.replace({ ...CHECKOUT, name: "Checkout service", teamId: WEB.id });
        backend.replies.set("update", () => error(409, "CONCURRENT_MODIFICATION", { message: "Conflict" }));

        await user.selectOptions(teamSelect(), "No team");
        await user.click(save());

        expect(await within(general()).findByRole("alert")).toHaveTextContent(
            "Someone else changed this app while you were editing.",
        );
        expect(within(general()).getByLabelText("App name")).toHaveValue("Checkout service");
        expect(teamSelect()).toHaveValue(WEB.id);
        expect(screen.getByRole("heading", { level: 1, name: "Checkout service" })).toBeInTheDocument();
        expect(screen.queryByText("App updated")).not.toBeInTheDocument();
    });

    it("says on the team field when the chosen team was deleted meanwhile", async () => {
        const backend = new AppsBackend();
        const { user } = await renderApp({ backend });
        backend.teams = [PAYMENTS];

        await user.selectOptions(teamSelect(), "Web");
        await user.click(save());

        expect(await within(general()).findByText("That team no longer exists. Choose another.")).toBeVisible();
    });

    it("is offered to a developer", async () => {
        await renderApp({ backend: new AppsBackend(undefined, undefined, FATUMA) });

        expect(general()).toBeInTheDocument();
    });

    it("is absent for a viewer, who still sees what was fixed at creation", async () => {
        await renderApp({ backend: new AppsBackend(undefined, undefined, KEVIN) });

        expect(screen.queryByRole("region", { name: "General" })).not.toBeInTheDocument();
        const facts = screen.getByRole("region", { name: "Fixed at creation" });
        expect(facts).toHaveTextContent("Amazon Web Services");
        expect(facts).toHaveTextContent("Cape Town (af-south-1)");
        expect(within(facts).queryByRole("textbox")).not.toBeInTheDocument();
        expect(within(facts).queryByRole("combobox")).not.toBeInTheDocument();
    });
});
